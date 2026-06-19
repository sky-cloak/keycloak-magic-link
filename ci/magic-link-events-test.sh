#!/usr/bin/env bash
#
# Phase 3: verifies native audit events. A Mode B issue produces an admin event (CREATE), a consume
# produces a LOGIN user event (auth_method=skycloak-magic-link), a failed consume produces a
# LOGIN_ERROR, and no event ever carries the link or token.
#
# Usage: ci/magic-link-events-test.sh [keycloak-version]   (default: 26.6.3)
set -euo pipefail

KC_VERSION="${1:-26.6.3}"
IMAGE="quay.io/keycloak/keycloak:${KC_VERSION}"
JAR="$(pwd)/target/keycloak-magic-link.jar"
PORT="${KC_PORT:-28120}"
BASE="http://localhost:${PORT}"
REALM="mlevents"; APP_CLIENT="mlapp"; REDIRECT="http://localhost/cb"

SUFFIX="$$-$(date +%s)"
KC_NAME="kc-mlev-${SUFFIX}"; MAIL_NAME="mail-mlev-${SUFFIX}"; NET_NAME="net-mlev-${SUFFIX}"
MAIL_HTTP_PORT=$((PORT + 1))

[[ -f "${JAR}" ]] || { echo "ERROR: ${JAR} missing - run mvn -Dkeycloak.version=${KC_VERSION} package" >&2; exit 1; }
cleanup() { docker rm -f "${KC_NAME}" "${MAIL_NAME}" >/dev/null 2>&1 || true; docker network rm "${NET_NAME}" >/dev/null 2>&1 || true; }
trap cleanup EXIT
kcadm() { docker exec "${KC_NAME}" /opt/keycloak/bin/kcadm.sh "$@"; }
fail() { echo "FAIL: $*" >&2; docker logs "${KC_NAME}" 2>&1 | tail -60 >&2; exit 1; }

echo ">> network + MailHog"
docker network create "${NET_NAME}" >/dev/null
docker run -d --name "${MAIL_NAME}" --network "${NET_NAME}" -p "${MAIL_HTTP_PORT}:8025" mailhog/mailhog:latest >/dev/null
for _ in $(seq 1 15); do curl -fsS "http://localhost:${MAIL_HTTP_PORT}/api/v2/messages" >/dev/null 2>&1 && break; sleep 1; done

echo ">> Keycloak ${IMAGE}"
docker run -d --name "${KC_NAME}" --network "${NET_NAME}" -p "${PORT}:8080" \
  -e KEYCLOAK_ADMIN=admin -e KEYCLOAK_ADMIN_PASSWORD=admin \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
  -v "${JAR}:/opt/keycloak/providers/keycloak-magic-link.jar:ro" \
  "${IMAGE}" start-dev >/dev/null
# kcadm runs inside the container over loopback, which is exempt from the realm sslRequired guard;
# the host-mapped token endpoint is not, so a host-side token request to master otherwise returns
# {"error":"invalid_request","error_description":"HTTPS required"}. Authenticate with kcadm first
# (also our readiness signal, retried until the bootstrap admin exists), relax master sslRequired
# over loopback, then mint the host-side master token the events queries need.
for _ in $(seq 1 90); do kcadm config credentials --server http://localhost:8080 --realm master --user admin --password admin >/dev/null 2>&1 && break; sleep 3; done
kcadm config credentials --server http://localhost:8080 --realm master --user admin --password admin >/dev/null || fail "kcadm could not authenticate to Keycloak"
kcadm update realms/master -s sslRequired=NONE >/dev/null
MTOK=""
for _ in $(seq 1 30); do
  MTOK=$(curl -fsS -d "grant_type=password&client_id=admin-cli&username=admin&password=admin" "${BASE}/realms/master/protocol/openid-connect/token" 2>/dev/null | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p') || true
  [[ -n "${MTOK}" ]] && break
  sleep 2
done
[[ -n "${MTOK}" ]] || { echo "last token response:"; curl -sS -d "grant_type=password&client_id=admin-cli&username=admin&password=admin" "${BASE}/realms/master/protocol/openid-connect/token" || true; fail "no master token"; }

echo ">> realm ${REALM} + events enabled + SMTP + clients + user"
kcadm create realms -s realm="${REALM}" -s enabled=true -s sslRequired=NONE >/dev/null
kcadm update realms/"${REALM}" -s 'smtpServer.host='"${MAIL_NAME}" -s 'smtpServer.port=1025' \
  -s 'smtpServer.from=noreply@example.test' -s 'smtpServer.auth=false' -s 'smtpServer.ssl=false' -s 'smtpServer.starttls=false' >/dev/null
curl -fsS -o /dev/null -X PUT "${BASE}/admin/realms/${REALM}" -H "Authorization: Bearer ${MTOK}" -H 'Content-Type: application/json' \
  -d '{"eventsEnabled":true,"adminEventsEnabled":true,"adminEventsDetailsEnabled":true,"eventsListeners":["jboss-logging"]}'
kcadm create clients -r "${REALM}" -s clientId="${APP_CLIENT}" -s publicClient=true -s standardFlowEnabled=true -s 'redirectUris=["'"${REDIRECT}"'"]' >/dev/null
kcadm create users -r "${REALM}" -s username=alice -s email=alice@example.test -s enabled=true -s emailVerified=false >/dev/null
kcadm create clients -r "${REALM}" -s clientId=ml-admin -s serviceAccountsEnabled=true -s publicClient=false -s secret=adminsecret >/dev/null
kcadm add-roles -r "${REALM}" --uusername service-account-ml-admin --cclientid realm-management --rolename manage-users >/dev/null
ADMIN_TOK=$(curl -fsS -d "grant_type=client_credentials&client_id=ml-admin&client_secret=adminsecret" "${BASE}/realms/${REALM}/protocol/openid-connect/token" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')
[[ -n "${ADMIN_TOK}" ]] || fail "no admin token"

echo ">> issue (Mode B, send=false) -> expect admin CREATE event"
HTTP=$(curl -sS -o /tmp/ev-body.json -w '%{http_code}' -X POST "${BASE}/realms/${REALM}/skycloak-magic-link" \
  -H "Authorization: Bearer ${ADMIN_TOK}" -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.test","clientId":"'"${APP_CLIENT}"'","redirectUri":"'"${REDIRECT}"'","send":false}')
[[ "${HTTP}" == "201" ]] || fail "issue not 201 (got ${HTTP})"
RLINK=$(sed -n 's/.*"link":"\([^"]*\)".*/\1/p' /tmp/ev-body.json)
TOKVAL=$(echo "${RLINK}" | sed -n 's/.*[?&]key=\([^&]*\).*/\1/p')
TOKSIG="${TOKVAL: -40}"   # unique tail (signature); the JWT header is common to all tokens
[[ -n "${TOKSIG}" ]] || fail "could not extract token from link"

sleep 1
AEV=$(curl -fsS "${BASE}/admin/realms/${REALM}/admin-events" -H "Authorization: Bearer ${MTOK}")
{ echo "${AEV}" | grep -q '"operationType":"CREATE"' && echo "${AEV}" | grep -q 'skycloak-magic-link'; } \
  || fail "no admin CREATE event for issue"
echo "   admin CREATE event present"
echo "   ADMIN EVENT JSON: $(echo "${AEV}" | head -c 500)"

echo ">> consume -> expect LOGIN user event"
curl -sS -o /dev/null "${RLINK}"            # GET confirm (no burn)
curl -sS -o /dev/null -X POST "${RLINK}"    # POST completes -> LOGIN
sleep 1
UEV=$(curl -fsS "${BASE}/admin/realms/${REALM}/events?type=LOGIN" -H "Authorization: Bearer ${MTOK}")
echo "${UEV}" | grep -q '"type":"LOGIN"' || fail "no LOGIN event for consume"
echo "${UEV}" | grep -q 'skycloak-magic-link' || fail "LOGIN event missing auth_method detail"
echo "   LOGIN event present (auth_method=skycloak-magic-link)"
echo "   LOGIN EVENT JSON: $(echo "${UEV}" | head -c 500)"

echo ">> failed consume (replay) -> expect LOGIN_ERROR"
curl -sS -o /dev/null -X POST "${RLINK}"    # already burned -> invalid_or_used -> LOGIN_ERROR
sleep 1
EEV=$(curl -fsS "${BASE}/admin/realms/${REALM}/events?type=LOGIN_ERROR" -H "Authorization: Bearer ${MTOK}")
echo "${EEV}" | grep -q '"type":"LOGIN_ERROR"' || fail "no LOGIN_ERROR event for failed consume"
echo "   LOGIN_ERROR event present"

echo ">> token/link must NOT appear in any event or in the server log"
ALL=$( (curl -fsS "${BASE}/admin/realms/${REALM}/admin-events" -H "Authorization: Bearer ${MTOK}"; \
        curl -fsS "${BASE}/admin/realms/${REALM}/events" -H "Authorization: Bearer ${MTOK}") )
echo "${ALL}" | grep -q "${TOKSIG}" && fail "token leaked into an event"
docker logs "${KC_NAME}" 2>&1 | grep -q "${TOKSIG}" && fail "token leaked into the server log"
echo "   token absent from events and logs"

echo ">> PASS: native audit events verified on Keycloak ${KC_VERSION}"
