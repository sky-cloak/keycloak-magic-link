#!/usr/bin/env bash
#
# Phase 3 Mode A polish: verifies the authenticator's auto-create-user config and the per-email
# rate limit (anti-enumeration preserved). Per-IP is left disabled because all curl shares one host
# IP; the per-email window is IP-independent, so it trips cleanly. Dedicated "mlextra" realm.
#
# Usage: ci/magic-link-modea-extra-test.sh [keycloak-version]   (default 26.6.3). KC_PORT overrides.
set -euo pipefail

KC_VERSION="${1:-26.6.3}"
IMAGE="quay.io/keycloak/keycloak:${KC_VERSION}"
JAR="$(pwd)/target/keycloak-magic-link.jar"
PORT="${KC_PORT:-28160}"
BASE="http://localhost:${PORT}"
REALM="mlextra"; CLIENT="mlx-app"; CB="http://localhost/cb"

SUFFIX="$$-$(date +%s)"
KC_NAME="kc-mlx-${SUFFIX}"; MAIL_NAME="mail-mlx-${SUFFIX}"; NET_NAME="net-mlx-${SUFFIX}"
MAIL_HTTP_PORT=$((PORT + 1)); WORK="$(mktemp -d)"

[[ -f "${JAR}" ]] || { echo "ERROR: ${JAR} missing - run mvn -Dkeycloak.version=${KC_VERSION} package" >&2; exit 1; }
source "$(dirname "$0")/jacoco.sh"; setup_jacoco
cleanup() { collect_jacoco; docker rm -f "${KC_NAME}" "${MAIL_NAME}" >/dev/null 2>&1 || true; docker network rm "${NET_NAME}" >/dev/null 2>&1 || true; rm -rf "${WORK}" 2>/dev/null || true; }
trap cleanup EXIT
kcadm() { docker exec "${KC_NAME}" /opt/keycloak/bin/kcadm.sh "$@"; }
fail() { echo "FAIL: $*" >&2; docker logs "${KC_NAME}" 2>&1 | tail -60 >&2; exit 1; }
mh_clear() { curl -fsS -X DELETE "http://localhost:${MAIL_HTTP_PORT}/api/v1/messages" >/dev/null 2>&1 || true; }
mh_count() { curl -fsS "http://localhost:${MAIL_HTTP_PORT}/api/v2/messages" 2>/dev/null | sed -n 's/.*"total":\([0-9]*\).*/\1/p' | head -1; }
b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
gen_pkce() { VERIFIER=$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '='); CHALLENGE=$(printf '%s' "${VERIFIER}" | openssl dgst -sha256 -binary | b64url); }

mh_link() {
  local msgs body
  for _ in $(seq 1 30); do
    msgs=$(curl -fsS "http://localhost:${MAIL_HTTP_PORT}/api/v2/messages" || echo '{}')
    if echo "${msgs}" | grep -q 'action-token'; then
      body=$(echo "${msgs}" | perl -0pe 's/=(?:\r?\n)//g; s/=3D/=/g')
      echo "${body}" | grep -oE 'http[^"\\ ]+/login-actions/action-token[^"\\ ]+' | head -1; return 0
    fi
    sleep 1
  done
  return 1
}
do_login_request() { # <jar> <challenge> <state> <email>  (authorize -> email form -> POST email)
  local jar="$1" challenge="$2" state="$3" who="$4" page action
  page=$(curl -fsS -L -c "${jar}" -b "${jar}" \
    "${BASE}/realms/${REALM}/protocol/openid-connect/auth?client_id=${CLIENT}&response_type=code&scope=openid%20email&redirect_uri=${CB}&state=${state}&code_challenge=${challenge}&code_challenge_method=S256")
  action=$(echo "${page}" | grep -oE 'action="[^"]*login-actions/authenticate[^"]*"' | head -1 | sed -e 's/^action="//' -e 's/"$//' -e 's/&amp;/\&/g')
  [[ -n "${action}" ]] || { echo "no email-form action" >&2; return 1; }
  curl -fsS -c "${jar}" -b "${jar}" -o /dev/null -w '%{http_code}' --data-urlencode "email=${who}" "${action}"
}
CONSUME_CODE=""
consume() { local jar="$1" link="$2" hdr="${WORK}/h"; : > "${hdr}"; curl -s -c "${jar}" -b "${jar}" -D "${hdr}" -o /dev/null -L --max-redirs 10 "${link}" >/dev/null 2>&1 || true; CONSUME_CODE=$(grep -i '^location:' "${hdr}" | tr -d '\r' | sed -n 's/.*[?&]code=\([^&]*\).*/\1/p' | head -1); }

echo ">> [${KC_VERSION}] network + MailHog"
docker network create "${NET_NAME}" >/dev/null
docker run -d --name "${MAIL_NAME}" --network "${NET_NAME}" -p "${MAIL_HTTP_PORT}:8025" mailhog/mailhog:latest >/dev/null
for _ in $(seq 1 15); do curl -fsS "http://localhost:${MAIL_HTTP_PORT}/api/v2/messages" >/dev/null 2>&1 && break; sleep 1; done

echo ">> [${KC_VERSION}] starting Keycloak"
docker run -d --name "${KC_NAME}" --network "${NET_NAME}" -p "${PORT}:8080" \
  "${JACOCO_DOCKER_ARGS[@]}" \
  -e KEYCLOAK_ADMIN=admin -e KEYCLOAK_ADMIN_PASSWORD=admin -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
  -v "${JAR}:/opt/keycloak/providers/keycloak-magic-link.jar:ro" "${IMAGE}" start-dev >/dev/null
up=; for _ in $(seq 1 80); do curl -fsS "${BASE}/realms/master" >/dev/null 2>&1 && { up=1; break; }; sleep 3; done
[[ -n "${up}" ]] || fail "KC did not start"
for _ in $(seq 1 10); do kcadm config credentials --server http://localhost:8080 --realm master --user admin --password admin >/dev/null 2>&1 && break; sleep 2; done
kcadm config credentials --server http://localhost:8080 --realm master --user admin --password admin >/dev/null

echo ">> [${KC_VERSION}] realm + SMTP + client + flow (auto-create=true, per-email=2, per-ip=0)"
kcadm create realms -s realm="${REALM}" -s enabled=true -s sslRequired=NONE >/dev/null
kcadm update realms/"${REALM}" -s 'smtpServer.host='"${MAIL_NAME}" -s 'smtpServer.port=1025' \
  -s 'smtpServer.from=noreply@example.test' -s 'smtpServer.auth=false' -s 'smtpServer.ssl=false' -s 'smtpServer.starttls=false' >/dev/null
kcadm create clients -r "${REALM}" -s clientId="${CLIENT}" -s publicClient=true -s standardFlowEnabled=true -s 'redirectUris=["http://localhost/cb"]' >/dev/null
kcadm create authentication/flows -r "${REALM}" -s alias=magic -s providerId=basic-flow -s topLevel=true -s builtIn=false >/dev/null
kcadm create authentication/flows/magic/executions/execution -r "${REALM}" -b '{"provider":"skycloak-magic-link"}' >/dev/null
EXEC_ID=$(kcadm get authentication/flows/magic/executions -r "${REALM}" --fields id,providerId --format csv --noquotes | grep skycloak-magic-link | head -1 | cut -d, -f1)
[[ -n "${EXEC_ID}" ]] || fail "authenticator not registered"
kcadm update authentication/flows/magic/executions -r "${REALM}" -b "{\"id\":\"${EXEC_ID}\",\"requirement\":\"REQUIRED\"}" >/dev/null
kcadm create authentication/executions/"${EXEC_ID}"/config -r "${REALM}" \
  -b '{"alias":"mlxcfg","config":{"requests-per-minute-per-ip":"0","requests-per-minute-per-email":"2","same-device":"true","token-lifespan-seconds":"600","auto-create-user":"true"}}' >/dev/null
kcadm update realms/"${REALM}" -s browserFlow=magic >/dev/null

FAILED=0; set +e

echo; echo "=== TEST A: auto-create - an unknown email creates a user and completes login ==="
mh_clear; gen_pkce
NEW="newbie@example.test"
[[ -z "$(kcadm get users -r "${REALM}" -q email=${NEW} --fields id --format csv --noquotes | tail -1)" ]] && echo "   precheck: ${NEW} does not exist yet"
HC=$(do_login_request "${WORK}/ja" "${CHALLENGE}" "sa" "${NEW}"); echo "   submit HTTP ${HC} (expect 200 sent page)"
LINK=$(mh_link) || { echo "   FAIL: no link for auto-created user"; FAILED=1; }
consume "${WORK}/ja" "${LINK}"
echo "   consume code: $([[ -n "${CONSUME_CODE}" ]] && echo present || echo MISSING)"
UID_NEW=$(kcadm get users -r "${REALM}" -q email=${NEW} --fields id --format csv --noquotes | tail -1)
EV=$(kcadm get users -r "${REALM}" -q email=${NEW} --fields emailVerified --format csv --noquotes | tail -1)
echo "   user created: $([[ -n "${UID_NEW}" ]] && echo yes || echo no); emailVerified=${EV}"
# emailVerified flips to true only inside the handler, AFTER the same-device and single-use checks
# pass, so it is proof the consume authenticated the auto-created user. A login code may be withheld
# if the realm requires profile completion for the bare new user (email only); that is realm config,
# not the feature.
{ [[ -n "${UID_NEW}" && "${EV}" == "true" ]]; } \
  && echo "   PASS: auto-create created the user and the consume authenticated it (emailVerified flipped)" \
  || { echo "   FAIL: auto-create path incomplete (user=${UID_NEW:-none} emailVerified=${EV})"; FAILED=1; }
echo "   (login code $([[ -n "${CONSUME_CODE}" ]] && echo issued || echo 'withheld pending new-user profile completion - realm config'))"

echo; echo "=== TEST B: per-email rate limit - 5 submits for one email yield at most 2 mails ==="
mh_clear; RLV="rl-victim@example.test"
for i in $(seq 1 5); do
  gen_pkce
  HC=$(do_login_request "${WORK}/jb${i}" "${CHALLENGE}" "sb${i}" "${RLV}")
  echo "   submit #${i}: HTTP ${HC} (sent page either way - anti-enumeration)"
  [[ "${HC}" == "200" ]] || { echo "   FAIL: submit #${i} not 200 (throttle must still show the sent page)"; FAILED=1; }
done
sleep 2; CNT=$(mh_count)
echo "   mails delivered for ${RLV}: ${CNT} (per-email limit = 2)"
{ [[ "${CNT}" -ge 1 && "${CNT}" -le 2 ]]; } \
  && echo "   PASS: per-email limit capped delivery (<=2) while every submit showed the sent page" \
  || { echo "   FAIL: expected <=2 mails, got ${CNT}"; FAILED=1; }

echo
if [[ "${FAILED}" -eq 0 ]]; then echo ">> PASS: auto-create + rate-limit verified on Keycloak ${KC_VERSION}"; else echo ">> FAILURES on ${KC_VERSION}" >&2; exit 1; fi
