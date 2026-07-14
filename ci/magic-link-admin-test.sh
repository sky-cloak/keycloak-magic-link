#!/usr/bin/env bash
#
# Integration test for the Mode B admin REST API (Phase 2): boots Keycloak + MailHog with the
# provider mounted, issues magic links via the admin API (service-account bearer with manage-users),
# and verifies issuance, the two-step scanner-safe consume, send=false, revoke, single-use, the
# 403 gate, and the realm-attribute role override.
#
# Usage: ci/magic-link-admin-test.sh [keycloak-version]   (default: 26.6.3)
set -euo pipefail

KC_VERSION="${1:-26.6.3}"
IMAGE="quay.io/keycloak/keycloak:${KC_VERSION}"
JAR="$(pwd)/target/keycloak-magic-link.jar"
PORT="${KC_PORT:-28100}"
BASE="http://localhost:${PORT}"
REALM="mladmin"
APP_CLIENT="mlapp"
REDIRECT="http://localhost/cb"

SUFFIX="$$-$(date +%s)"
KC_NAME="kc-mladmin-${SUFFIX}"; MAIL_NAME="mail-mladmin-${SUFFIX}"; NET_NAME="net-mladmin-${SUFFIX}"
MAIL_HTTP_PORT=$((PORT + 1))

[[ -f "${JAR}" ]] || { echo "ERROR: ${JAR} missing - run mvn -Dkeycloak.version=${KC_VERSION} package" >&2; exit 1; }
source "$(dirname "$0")/jacoco.sh"; setup_jacoco

cleanup() { collect_jacoco; docker rm -f "${KC_NAME}" "${MAIL_NAME}" >/dev/null 2>&1 || true; docker network rm "${NET_NAME}" >/dev/null 2>&1 || true; }
trap cleanup EXIT
kcadm() { docker exec "${KC_NAME}" /opt/keycloak/bin/kcadm.sh "$@"; }
mail_count() { curl -fsS "http://localhost:${MAIL_HTTP_PORT}/api/v2/messages" 2>/dev/null | sed -n 's/.*"total":\([0-9]*\).*/\1/p' || echo 0; }
fail() { echo "FAIL: $*" >&2; docker logs "${KC_NAME}" 2>&1 | tail -60 >&2; exit 1; }

echo ">> network + MailHog"
docker network create "${NET_NAME}" >/dev/null
docker run -d --name "${MAIL_NAME}" --network "${NET_NAME}" -p "${MAIL_HTTP_PORT}:8025" mailhog/mailhog:latest >/dev/null
for _ in $(seq 1 15); do curl -fsS "http://localhost:${MAIL_HTTP_PORT}/api/v2/messages" >/dev/null 2>&1 && break; sleep 1; done

echo ">> Keycloak ${IMAGE}"
docker run -d --name "${KC_NAME}" --network "${NET_NAME}" -p "${PORT}:8080" \
  "${JACOCO_DOCKER_ARGS[@]}" \
  -e KEYCLOAK_ADMIN=admin -e KEYCLOAK_ADMIN_PASSWORD=admin \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
  -v "${JAR}:/opt/keycloak/providers/keycloak-magic-link.jar:ro" \
  "${IMAGE}" start-dev >/dev/null
up=; for _ in $(seq 1 80); do curl -fsS "${BASE}/realms/master" >/dev/null 2>&1 && { up=1; break; }; sleep 3; done
[[ -n "${up}" ]] || fail "Keycloak did not start"

for i in $(seq 1 10); do kcadm config credentials --server http://localhost:8080 --realm master --user admin --password admin >/dev/null 2>&1 && break; sleep 2; done
kcadm config credentials --server http://localhost:8080 --realm master --user admin --password admin >/dev/null

echo ">> realm ${REALM} + SMTP + clients + user"
kcadm create realms -s realm="${REALM}" -s enabled=true -s sslRequired=NONE >/dev/null
kcadm update realms/"${REALM}" \
  -s 'smtpServer.host='"${MAIL_NAME}" -s 'smtpServer.port=1025' -s 'smtpServer.from=noreply@example.test' \
  -s 'smtpServer.auth=false' -s 'smtpServer.ssl=false' -s 'smtpServer.starttls=false' >/dev/null
kcadm create clients -r "${REALM}" -s clientId="${APP_CLIENT}" -s publicClient=true -s standardFlowEnabled=true \
  -s 'redirectUris=["'"${REDIRECT}"'"]' >/dev/null
kcadm create users -r "${REALM}" -s username=alice -s email=alice@example.test -s enabled=true -s emailVerified=false >/dev/null

# Service-account client WITH manage-users (the admin caller).
kcadm create clients -r "${REALM}" -s clientId=ml-admin -s serviceAccountsEnabled=true -s publicClient=false -s secret=adminsecret >/dev/null
kcadm add-roles -r "${REALM}" --uusername service-account-ml-admin --cclientid realm-management --rolename manage-users >/dev/null
# Service-account client WITHOUT any realm-management role (the 403 case + later override case).
kcadm create clients -r "${REALM}" -s clientId=ml-noperm -s serviceAccountsEnabled=true -s publicClient=false -s secret=nopermsecret >/dev/null

tok() { curl -fsS -d "grant_type=client_credentials&client_id=$1&client_secret=$2" "${BASE}/realms/${REALM}/protocol/openid-connect/token" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p'; }
ADMIN_TOK="$(tok ml-admin adminsecret)"; [[ -n "${ADMIN_TOK}" ]] || fail "no admin token"
NOPERM_TOK="$(tok ml-noperm nopermsecret)"; [[ -n "${NOPERM_TOK}" ]] || fail "no noperm token"

issue() { # $1=token $2=json-body  -> prints "HTTP\n<body>"
  curl -sS -o /tmp/mladmin-body.json -w '%{http_code}' -X POST "${BASE}/realms/${REALM}/skycloak-magic-link" \
    -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -d "$2"; echo; cat /tmp/mladmin-body.json; }
last_link() { curl -fsS "http://localhost:${MAIL_HTTP_PORT}/api/v2/messages" | perl -0pe 's/=(?:\r?\n)//g' \
    | grep -oE 'http[^"\\ ]+/realms/'"${REALM}"'/skycloak-magic-link/consume[^"\\ ]+' | head -1; }

echo ">> TEST 1: 403 without manage-users"
C=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "${BASE}/realms/${REALM}/skycloak-magic-link" \
  -H "Authorization: Bearer ${NOPERM_TOK}" -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.test","clientId":"'"${APP_CLIENT}"'","redirectUri":"'"${REDIRECT}"'"}')
[[ "${C}" == "403" ]] || fail "expected 403 without manage-users, got ${C}"
echo "   403 confirmed"

echo ">> TEST 2: issue + email + two-step + scanner-safe + single-use"
MB=$(mail_count)
OUT=$(issue "${ADMIN_TOK}" '{"email":"alice@example.test","clientId":"'"${APP_CLIENT}"'","redirectUri":"'"${REDIRECT}"'"}')
echo "   issue -> ${OUT}"
echo "${OUT}" | head -1 | grep -q '^201' || fail "expected 201 from issue"
for _ in $(seq 1 30); do [[ "$(mail_count)" -gt "${MB}" ]] && break; sleep 1; done
LINK="$(last_link)"; [[ -n "${LINK}" ]] || fail "no email link arrived"
echo "   link: ${LINK:0:80}..."
# GET shows the confirm page and must NOT burn (200, contains a POST form).
G1=$(curl -sS -o /tmp/c1.html -w '%{http_code}' "${LINK}"); grep -qi 'method="POST"' /tmp/c1.html || fail "GET did not render confirm form"
[[ "${G1}" == "200" ]] || fail "expected 200 confirm page on GET, got ${G1}"
# A scanner prefetch (second GET) must also not burn.
curl -sS -o /dev/null "${LINK}"
# POST completes -> 302 to redirect with a code.
PH=$(curl -sS -o /dev/null -D - -X POST "${LINK}")
SL=$(printf '%s' "${PH}" | head -1 | tr -d '\r')
LOC=$(printf '%s' "${PH}" | awk 'tolower($1)=="location:"{print $2}' | tr -d '\r')
echo "   confirm GET=${G1} ; POST status: ${SL}"
echo "   POST Location: ${LOC:0:110}"
if ! { echo "${SL}" | grep -qE ' 30[0-9]' && echo "${LOC}" | grep -q "${REDIRECT}" && echo "${LOC}" | grep -q 'code='; }; then
  echo "   --- full POST response head ---"; printf '%s' "${PH}" | head -10
  fail "POST consume did not 30x to ${REDIRECT} with a code"
fi
# Single-use: replay POST must NOT 302 with a code.
PH2=$(curl -sS -o /dev/null -D - -X POST "${LINK}")
LOC2=$(printf '%s' "${PH2}" | awk 'tolower($1)=="location:"{print $2}' | tr -d '\r')
if echo "${LOC2}" | grep -q 'code='; then fail "replay POST should not re-issue a code"; else echo "   single-use confirmed"; fi

echo ">> TEST 2b: request validation errors"
validate() { curl -sS -o /dev/null -w '%{http_code}' -X POST "${BASE}/realms/${REALM}/skycloak-magic-link" -H "Authorization: Bearer ${ADMIN_TOK}" -H 'Content-Type: application/json' -d "$1"; }
[[ "$(validate '{')" == "400" ]] || fail "malformed JSON should be 400"
[[ "$(validate '{"email":"alice@example.test"}')" == "400" ]] || fail "missing client/redirect should be 400"
[[ "$(validate '{"email":"nobody@example.test","clientId":"mlapp","redirectUri":"http://localhost/cb","send":false}')" == "404" ]] || fail "unknown user should be 404"
[[ "$(validate '{"email":"alice@example.test","clientId":"missing","redirectUri":"http://localhost/cb","send":false}')" == "400" ]] || fail "unknown client should be 400"
[[ "$(validate '{"email":"alice@example.test","clientId":"mlapp","redirectUri":"http://not-registered/cb","send":false}')" == "400" ]] || fail "unregistered redirect should be 400"
VC=$(curl -sS -o /dev/null -w '%{http_code}' -X DELETE "${BASE}/realms/${REALM}/skycloak-magic-link/not-a-pending-link" -H "Authorization: Bearer ${ADMIN_TOK}")
[[ "${VC}" == "404" ]] || fail "unknown revoke should be 404"

echo ">> TEST 3: send=false returns link in body, not emailed, not logged"
MB=$(mail_count)
OUT=$(issue "${ADMIN_TOK}" '{"email":"alice@example.test","clientId":"'"${APP_CLIENT}"'","redirectUri":"'"${REDIRECT}"'","send":false}')
echo "   issue(send=false) -> $(echo "${OUT}" | head -1) ; body has link? $(echo "${OUT}" | tail -1 | grep -c '"link"')"
echo "${OUT}" | tail -1 | grep -q '"link"' || fail "send=false must return link in body"
RLINK=$(echo "${OUT}" | tail -1 | sed -n 's/.*"link":"\([^"]*\)".*/\1/p')
sleep 2; [[ "$(mail_count)" == "${MB}" ]] || fail "send=false must not email"
TOKVAL=$(echo "${RLINK}" | sed -n 's/.*[?&]key=\([^&]*\).*/\1/p')
docker logs "${KC_NAME}" 2>&1 | grep -q "${TOKVAL}" && fail "raw token leaked into logs" || echo "   not emailed, token absent from logs"

echo ">> TEST 4: revoke -> consume fails"
OUT=$(issue "${ADMIN_TOK}" '{"email":"alice@example.test","clientId":"'"${APP_CLIENT}"'","redirectUri":"'"${REDIRECT}"'","send":false}')
ID=$(echo "${OUT}" | tail -1 | sed -n 's/.*"id":"\([^"]*\)".*/\1/p'); RLINK=$(echo "${OUT}" | tail -1 | sed -n 's/.*"link":"\([^"]*\)".*/\1/p')
DC=$(curl -sS -o /dev/null -w '%{http_code}' -X DELETE "${BASE}/realms/${REALM}/skycloak-magic-link/${ID}" -H "Authorization: Bearer ${ADMIN_TOK}")
[[ "${DC}" == "204" ]] || fail "expected 204 from revoke, got ${DC}"
PRH=$(curl -sS -o /dev/null -D - -X POST "${RLINK}")
LOCR=$(printf '%s' "${PRH}" | awk 'tolower($1)=="location:"{print $2}' | tr -d '\r')
if echo "${LOCR}" | grep -q 'code='; then fail "revoked link should not complete"; else echo "   revoke confirmed (204; consume blocked)"; fi

echo ">> TEST 5: realm-attribute role override"
# The override key is hyphenated (no dots), so kcadm -s sets it directly - no host admin
# token or REST round-trip needed (kcadm is already authenticated in-container).
kcadm update realms/"${REALM}" -s 'attributes.skycloak-magic-link-issue-role=view-users' >/dev/null
kcadm add-roles -r "${REALM}" --uusername service-account-ml-noperm --cclientid realm-management --rolename view-users >/dev/null
OVR_TOK="$(tok ml-noperm nopermsecret)"; [[ -n "${OVR_TOK}" ]] || fail "no override token"
OC=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "${BASE}/realms/${REALM}/skycloak-magic-link" \
  -H "Authorization: Bearer ${OVR_TOK}" -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.test","clientId":"'"${APP_CLIENT}"'","redirectUri":"'"${REDIRECT}"'","send":false}')
[[ "${OC}" == "201" ]] || fail "override role (view-users) should allow issue, got ${OC}"
echo "   override role honored (view-users -> 201)"

echo ">> PASS: Mode B admin API verified on Keycloak ${KC_VERSION}"
