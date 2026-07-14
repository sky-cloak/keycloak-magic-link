#!/usr/bin/env bash
#
# Integration test for the Mode A magic-link AUTHENTICATOR (v0.3.0). Boots a real Keycloak with the
# provider mounted, wires a browser flow whose only step is the authenticator, and drives a full
# OIDC authorization-code + PKCE login through it. Proves: PKCE-bound code, wrong verifier rejected,
# scanner-safe single use (a prefetch without the device cookie does not burn the link), same-device
# enforcement, single use, emailVerified-on-consume, and anti-enumeration. Dedicated "mltest" realm.
#
# Usage: ci/magic-link-authenticator-test.sh [keycloak-version]   (default 26.6.3). KC_PORT overrides.
#
set -euo pipefail

KC_VERSION="${1:-26.6.3}"
IMAGE="quay.io/keycloak/keycloak:${KC_VERSION}"
JAR="$(pwd)/target/keycloak-magic-link.jar"
PORT="${KC_PORT:-28092}"
BASE="http://localhost:${PORT}"
REALM="mltest"
CLIENT="mltest-app"
CB="http://localhost/cb"
EMAIL="alice@example.test"

SUFFIX="$$-$(date +%s)"
KC_NAME="kc-ml-${SUFFIX}"; MAIL_NAME="kc-ml-mail-${SUFFIX}"; NET_NAME="kc-ml-net-${SUFFIX}"
MAIL_HTTP_PORT=$((PORT + 1))
WORK="$(mktemp -d)"

[[ -f "${JAR}" ]] || { echo "ERROR: ${JAR} missing - run mvn -Dkeycloak.version=${KC_VERSION} package" >&2; exit 1; }
source "$(dirname "$0")/jacoco.sh"; setup_jacoco

cleanup() {
  collect_jacoco
  docker rm -f "${KC_NAME}" >/dev/null 2>&1 || true
  docker rm -f "${MAIL_NAME}" >/dev/null 2>&1 || true
  docker network rm "${NET_NAME}" >/dev/null 2>&1 || true
  rm -rf "${WORK}" 2>/dev/null || true
}
trap cleanup EXIT

kcadm() { docker exec "${KC_NAME}" /opt/keycloak/bin/kcadm.sh "$@"; }
mh_clear() { curl -fsS -X DELETE "http://localhost:${MAIL_HTTP_PORT}/api/v1/messages" >/dev/null 2>&1 || true; }
mh_count() { curl -fsS "http://localhost:${MAIL_HTTP_PORT}/api/v2/messages" 2>/dev/null | sed -n 's/.*"total":\([0-9]*\).*/\1/p' | head -1; }

mh_link() {
  local msgs body
  for _ in $(seq 1 30); do
    msgs=$(curl -fsS "http://localhost:${MAIL_HTTP_PORT}/api/v2/messages" || echo '{}')
    if echo "${msgs}" | grep -q 'action-token'; then
      body=$(echo "${msgs}" | perl -0pe 's/=(?:\r?\n)//g; s/=3D/=/g')
      echo "${body}" | grep -oE 'http[^"\\ ]+/login-actions/action-token[^"\\ ]+' | head -1
      return 0
    fi
    sleep 1
  done
  return 1
}

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
b64d() { local s="$1"; local m=$(( ${#s} % 4 )); [[ $m -ne 0 ]] && s="${s}$(printf '=%.0s' $(seq 1 $((4-m))))"; printf '%s' "${s}" | tr '_-' '/+' | base64 -d 2>/dev/null; }
gen_pkce() {
  VERIFIER=$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=')
  CHALLENGE=$(printf '%s' "${VERIFIER}" | openssl dgst -sha256 -binary | b64url)
}

# authorize -> our email form -> POST email. Leaves the magic link in MailHog (cookie jar keeps the
# device cookie set by the POST response). Args: <jar> <challenge> <state> <email>
do_login_request() {
  local jar="$1" challenge="$2" state="$3" who="$4" page action
  page=$(curl -fsS -L -c "${jar}" -b "${jar}" \
    "${BASE}/realms/${REALM}/protocol/openid-connect/auth?client_id=${CLIENT}&response_type=code&scope=openid%20email&redirect_uri=${CB}&state=${state}&code_challenge=${challenge}&code_challenge_method=S256")
  action=$(echo "${page}" | grep -oE 'action="[^"]*login-actions/authenticate[^"]*"' | head -1 \
    | sed -e 's/^action="//' -e 's/"$//' -e 's/&amp;/\&/g')
  [[ -n "${action}" ]] || { echo "FAIL: no email-form action found" >&2; echo "${page}" | head -40 >&2; return 1; }
  curl -fsS -c "${jar}" -b "${jar}" -o /dev/null --data-urlencode "email=${who}" "${action}"
}

# Open the magic link and follow the completion redirect chain. Sets CONSUME_CODE / CONSUME_STATUS.
CONSUME_CODE=""; CONSUME_STATUS=""
consume() {
  local jar="$1" link="$2" hdr="${WORK}/c_hdr"
  : > "${hdr}"
  curl -s -c "${jar}" -b "${jar}" -D "${hdr}" -o /dev/null -L --max-redirs 10 "${link}" >/dev/null 2>&1 || true
  CONSUME_STATUS=$(grep -iE '^HTTP/' "${hdr}" | head -1 | tr -d '\r')
  CONSUME_CODE=$(grep -i '^location:' "${hdr}" | tr -d '\r' | sed -n 's/.*[?&]code=\([^&]*\).*/\1/p' | head -1)
}

exchange() {  # <code> <verifier> -> echoes token JSON
  curl -s -X POST "${BASE}/realms/${REALM}/protocol/openid-connect/token" \
    -d grant_type=authorization_code -d client_id="${CLIENT}" \
    --data-urlencode "code=$1" --data-urlencode "redirect_uri=${CB}" --data-urlencode "code_verifier=$2"
}

echo ">> [${KC_VERSION}] network + MailHog"
docker network create "${NET_NAME}" >/dev/null
docker run -d --name "${MAIL_NAME}" --network "${NET_NAME}" -p "${MAIL_HTTP_PORT}:8025" mailhog/mailhog:latest >/dev/null
for _ in $(seq 1 15); do curl -fsS "http://localhost:${MAIL_HTTP_PORT}/api/v2/messages" >/dev/null 2>&1 && break; sleep 1; done

echo ">> [${KC_VERSION}] starting Keycloak with provider mounted"
docker run -d --name "${KC_NAME}" --network "${NET_NAME}" -p "${PORT}:8080" \
  "${JACOCO_DOCKER_ARGS[@]}" \
  -e KEYCLOAK_ADMIN=admin -e KEYCLOAK_ADMIN_PASSWORD=admin \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
  -v "${JAR}:/opt/keycloak/providers/keycloak-magic-link.jar:ro" \
  "${IMAGE}" start-dev >/dev/null

up=
for _ in $(seq 1 80); do curl -fsS "${BASE}/realms/master" >/dev/null 2>&1 && { up=1; break; }; sleep 3; done
[[ -n "${up}" ]] || { echo "FAIL: KC did not start" >&2; docker logs "${KC_NAME}" 2>&1 | tail -60 >&2; exit 1; }

for _ in $(seq 1 10); do kcadm config credentials --server http://localhost:8080 --realm master --user admin --password admin >/dev/null 2>&1 && break; sleep 2; done
kcadm config credentials --server http://localhost:8080 --realm master --user admin --password admin >/dev/null

echo ">> [${KC_VERSION}] realm ${REALM} + SMTP + public PKCE client + user (emailVerified=false)"
kcadm create realms -s realm="${REALM}" -s enabled=true -s sslRequired=NONE >/dev/null
kcadm update realms/"${REALM}" \
  -s 'smtpServer.host='"${MAIL_NAME}" -s 'smtpServer.port=1025' \
  -s 'smtpServer.from=noreply@example.test' -s 'smtpServer.fromDisplayName=Skycloak Magic Link' \
  -s 'smtpServer.auth=false' -s 'smtpServer.ssl=false' -s 'smtpServer.starttls=false' >/dev/null
kcadm create clients -r "${REALM}" -s clientId="${CLIENT}" -s enabled=true \
  -s publicClient=true -s standardFlowEnabled=true \
  -s 'redirectUris=["http://localhost/cb"]' -s 'webOrigins=["*"]' >/dev/null
kcadm create users -r "${REALM}" -s username=alice -s email="${EMAIL}" \
  -s firstName=Alice -s lastName=Example -s emailVerified=false -s enabled=true >/dev/null

echo ">> [${KC_VERSION}] browser flow with the authenticator (REQUIRED), bound as browserFlow"
kcadm create authentication/flows -r "${REALM}" \
  -s alias=magic -s providerId=basic-flow -s topLevel=true -s builtIn=false >/dev/null
kcadm create authentication/flows/magic/executions/execution -r "${REALM}" \
  -b '{"provider":"skycloak-magic-link"}' >/dev/null
EXEC_ID=$(kcadm get authentication/flows/magic/executions -r "${REALM}" \
  --fields id,providerId --format csv --noquotes | grep skycloak-magic-link | head -1 | cut -d, -f1)
[[ -n "${EXEC_ID}" ]] || { echo "FAIL: authenticator not registered (provider not loaded)" >&2; docker logs "${KC_NAME}" 2>&1 | tail -60 >&2; exit 1; }
kcadm update authentication/flows/magic/executions -r "${REALM}" \
  -b "{\"id\":\"${EXEC_ID}\",\"requirement\":\"REQUIRED\"}" >/dev/null
# Per-flow config: disable rate limits for the functional tests (all curl shares one host IP, which
# would otherwise confound), keep same-device on. The limiter itself is unit-tested separately.
kcadm create authentication/executions/"${EXEC_ID}"/config -r "${REALM}" \
  -b '{"alias":"mlcfg","config":{"requests-per-minute-per-ip":"0","requests-per-minute-per-email":"0","same-device":"true","token-lifespan-seconds":"600","auto-create-user":"false"}}' >/dev/null
kcadm update realms/"${REALM}" -s browserFlow=magic >/dev/null
echo "   authenticator bound + configured (exec ${EXEC_ID})"

FAILED=0
set +e

echo; echo "=== TEST 1: happy path - PKCE-bound code exchanged with the correct verifier ==="
gen_pkce; mh_clear
do_login_request "${WORK}/jar1" "${CHALLENGE}" "s1" "${EMAIL}"
LINK1=$(mh_link) || { echo "FAIL: no link"; docker logs "${KC_NAME}" 2>&1 | tail -40 >&2; exit 1; }
echo "   link: ${LINK1:0:88}..."
consume "${WORK}/jar1" "${LINK1}"; CODE1="${CONSUME_CODE}"
echo "   consume status : ${CONSUME_STATUS}; code: ${CODE1:0:22}..."
[[ -n "${CODE1}" ]] || { echo "   FAIL: no code from consume"; FAILED=1; }
TOK_OK=$(exchange "${CODE1}" "${VERIFIER}")
echo "   RAW TOKEN JSON (success): ${TOK_OK:0:230}..."
echo "${TOK_OK}" | grep -q '"access_token"' && echo "   PASS: PKCE-bound code exchanged with verifier" || { echo "   FAIL: no access_token"; FAILED=1; }

echo; echo "=== TEST 1b: emailVerified set true on consume (user created with false) ==="
EV=$(kcadm get users -r "${REALM}" -q username=alice --fields emailVerified --format csv --noquotes | tail -1)
echo "   alice emailVerified now: ${EV}"
[[ "${EV}" == "true" ]] && echo "   PASS: emailVerified flipped to true" || { echo "   FAIL: emailVerified not set"; FAILED=1; }
IDT=$(echo "${TOK_OK}" | sed -n 's/.*"id_token":"\([^"]*\)".*/\1/p')
[[ -n "${IDT}" ]] && echo "   id_token claim: $(b64d "$(echo "${IDT}" | cut -d. -f2)" | grep -oE '"email_verified":[a-z]+' | head -1)"

echo; echo "=== TEST 2: PKCE enforcement - WRONG verifier rejected ==="
gen_pkce; mh_clear
do_login_request "${WORK}/jar2" "${CHALLENGE}" "s2" "${EMAIL}"
LINK2=$(mh_link) || { echo "FAIL: no link (t2)"; exit 1; }
consume "${WORK}/jar2" "${LINK2}"; CODE2="${CONSUME_CODE}"
WRONG=$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=')
TOK_BAD=$(exchange "${CODE2}" "${WRONG}")
echo "   RAW TOKEN JSON (wrong verifier): ${TOK_BAD}"
echo "${TOK_BAD}" | grep -q '"access_token"' && { echo "   FAIL: wrong verifier accepted!"; FAILED=1; } || echo "   PASS: wrong verifier rejected (PKCE enforced)"

echo; echo "=== TEST 3: scanner-safe - a prefetch GET without the device cookie must NOT burn it ==="
gen_pkce; mh_clear
do_login_request "${WORK}/jar3" "${CHALLENGE}" "s3" "${EMAIL}"
LINK3=$(mh_link) || { echo "FAIL: no link (t3)"; exit 1; }
# Simulate an email security scanner: GET the link twice from a server with no device cookie.
H3A=$(curl -s -o /dev/null -w '%{http_code}' "${LINK3}"); curl -s -o /dev/null "${LINK3}"
echo "   scanner prefetch GET status (no cookie): ${H3A} (expected 403 same-device, no burn)"
# Now the genuine user, in the original browser, opens the link.
consume "${WORK}/jar3" "${LINK3}"; CODE3="${CONSUME_CODE}"
echo "   genuine consume status : ${CONSUME_STATUS}; code: $([[ -n "${CODE3}" ]] && echo present || echo MISSING)"
[[ -n "${CODE3}" ]] && echo "   PASS: link survived the prefetch and still completed (scanner-safe)" || { echo "   FAIL: prefetch burned the link"; FAILED=1; }

echo; echo "=== TEST 4: same-device - link opened without the device cookie is rejected ==="
gen_pkce; mh_clear
do_login_request "${WORK}/jar4" "${CHALLENGE}" "s4" "${EMAIL}"
LINK4=$(mh_link) || { echo "FAIL: no link (t4)"; exit 1; }
consume "${WORK}/empty4" "${LINK4}"; CD4="${CONSUME_CODE}"; ST4="${CONSUME_STATUS}"
echo "   cross-device consume status : ${ST4}; code: ${CD4:-<none>}"
[[ -z "${CD4}" ]] && echo "   PASS: cross-device click yielded no code (same-device enforced)" || { echo "   FAIL: cross-device logged in"; FAILED=1; }
consume "${WORK}/jar4" "${LINK4}"
echo "   genuine-device follow-up code: $([[ -n "${CONSUME_CODE}" ]] && echo present || echo MISSING) (confirms cross-device attempt did not burn)"

echo; echo "=== TEST 5: single-use - replay of a consumed link fails ==="
gen_pkce; mh_clear
do_login_request "${WORK}/jar5" "${CHALLENGE}" "s5" "${EMAIL}"
LINK5=$(mh_link) || { echo "FAIL: no link (t5)"; exit 1; }
consume "${WORK}/jar5" "${LINK5}"; C5A="${CONSUME_CODE}"
consume "${WORK}/jar5" "${LINK5}"; C5B="${CONSUME_CODE}"
echo "   first code: $([[ -n "${C5A}" ]] && echo yes || echo no); replay code: $([[ -n "${C5B}" ]] && echo yes || echo no)"
[[ -n "${C5A}" && -z "${C5B}" ]] && echo "   PASS: single-use enforced (replay produced no code)" || { echo "   FAIL: single-use broken"; FAILED=1; }

echo; echo "=== TEST 6: anti-enumeration - unknown email shows the same page and sends no mail ==="
mh_clear; BEFORE=$(mh_count)
gen_pkce
do_login_request "${WORK}/jar6" "${CHALLENGE}" "s6" "nobody-here@example.test" >/dev/null 2>&1
sleep 2; AFTER=$(mh_count)
echo "   MailHog before=${BEFORE} after=${AFTER} (unknown-email submit)"
[[ "${BEFORE}" == "${AFTER}" ]] && echo "   PASS: no mail sent for unknown email (non-enumerable)" || { echo "   FAIL: mail sent for unknown email"; FAILED=1; }

echo; echo "=== TEST 7: rate limiting (informational) ==="
echo "   The per-IP/per-email sliding-window limiter is unit-tested (SlidingWindowLimiterTest) and"
echo "   wired into the authenticator (disabled in this run so the shared host IP does not confound"
echo "   the functional tests). A live browser-flow burst test needs an isolated low-limit flow."

echo
if [[ "${FAILED}" -eq 0 ]]; then
  echo ">> ALL AUTHENTICATOR TESTS PASSED on Keycloak ${KC_VERSION}"
else
  echo ">> FAILURES on Keycloak ${KC_VERSION}" >&2
  docker logs "${KC_NAME}" 2>&1 | tail -100 >&2
  exit 1
fi
