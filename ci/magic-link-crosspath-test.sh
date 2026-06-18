#!/usr/bin/env bash
#
# Cross-path replay regression test (v0.3.0). Proves the same-device bypass is closed: a Mode A
# (same-device) magic-link token, which carries a device nonce and is meant to be consumed only via
# the action-token handler (which checks the device cookie), must be REJECTED when replayed against
# Mode B's cookieless resource consume at /realms/{r}/skycloak-magic-link/consume. Otherwise an
# intercepted Mode A link could be completed without the device cookie, defeating same-device.
#
# The fix under test: MagicLinkResource.decode() returns null when token.getDeviceNonce() != null.
#
# Usage: ci/magic-link-crosspath-test.sh [keycloak-version]   (default 26.6.3). KC_PORT overrides.
#
set -euo pipefail

KC_VERSION="${1:-26.6.3}"
IMAGE="quay.io/keycloak/keycloak:${KC_VERSION}"
JAR="$(pwd)/target/keycloak-magic-link.jar"
PORT="${KC_PORT:-28094}"
BASE="http://localhost:${PORT}"
REALM="mltest"
CLIENT="mltest-app"
CB="http://localhost/cb"
EMAIL="alice@example.test"

SUFFIX="$$-$(date +%s)"
KC_NAME="kc-mlx-${SUFFIX}"; MAIL_NAME="kc-mlx-mail-${SUFFIX}"; NET_NAME="kc-mlx-net-${SUFFIX}"
MAIL_HTTP_PORT=$((PORT + 1))
WORK="$(mktemp -d)"

[[ -f "${JAR}" ]] || { echo "ERROR: ${JAR} missing - run mvn -Dkeycloak.version=${KC_VERSION} package" >&2; exit 1; }

cleanup() {
  docker rm -f "${KC_NAME}" >/dev/null 2>&1 || true
  docker rm -f "${MAIL_NAME}" >/dev/null 2>&1 || true
  docker network rm "${NET_NAME}" >/dev/null 2>&1 || true
  rm -rf "${WORK}" 2>/dev/null || true
}
trap cleanup EXIT

kcadm() { docker exec "${KC_NAME}" /opt/keycloak/bin/kcadm.sh "$@"; }
mh_clear() { curl -fsS -X DELETE "http://localhost:${MAIL_HTTP_PORT}/api/v1/messages" >/dev/null 2>&1 || true; }

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
gen_pkce() {
  VERIFIER=$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=')
  CHALLENGE=$(printf '%s' "${VERIFIER}" | openssl dgst -sha256 -binary | b64url)
}

# authorize -> email form -> POST email. Cookie jar keeps the device cookie set by the POST response.
do_login_request() {
  local jar="$1" challenge="$2" state="$3" who="$4" page action
  page=$(curl -fsS -L -c "${jar}" -b "${jar}" \
    "${BASE}/realms/${REALM}/protocol/openid-connect/auth?client_id=${CLIENT}&response_type=code&scope=openid%20email&redirect_uri=${CB}&state=${state}&code_challenge=${challenge}&code_challenge_method=S256")
  action=$(echo "${page}" | grep -oE 'action="[^"]*login-actions/authenticate[^"]*"' | head -1 \
    | sed -e 's/^action="//' -e 's/"$//' -e 's/&amp;/\&/g')
  [[ -n "${action}" ]] || { echo "FAIL: no email-form action found" >&2; return 1; }
  curl -fsS -c "${jar}" -b "${jar}" -o /dev/null --data-urlencode "email=${who}" "${action}"
}

CONSUME_CODE=""; CONSUME_STATUS=""
consume() {
  local jar="$1" link="$2" hdr="${WORK}/c_hdr"
  : > "${hdr}"
  curl -s -c "${jar}" -b "${jar}" -D "${hdr}" -o /dev/null -L --max-redirs 10 "${link}" >/dev/null 2>&1 || true
  CONSUME_STATUS=$(grep -iE '^HTTP/' "${hdr}" | head -1 | tr -d '\r')
  CONSUME_CODE=$(grep -i '^location:' "${hdr}" | tr -d '\r' | sed -n 's/.*[?&]code=\([^&]*\).*/\1/p' | head -1)
}

echo ">> [${KC_VERSION}] network + MailHog"
docker network create "${NET_NAME}" >/dev/null
docker run -d --name "${MAIL_NAME}" --network "${NET_NAME}" -p "${MAIL_HTTP_PORT}:8025" mailhog/mailhog:latest >/dev/null
for _ in $(seq 1 15); do curl -fsS "http://localhost:${MAIL_HTTP_PORT}/api/v2/messages" >/dev/null 2>&1 && break; sleep 1; done

echo ">> [${KC_VERSION}] starting Keycloak with provider mounted"
docker run -d --name "${KC_NAME}" --network "${NET_NAME}" -p "${PORT}:8080" \
  -e KEYCLOAK_ADMIN=admin -e KEYCLOAK_ADMIN_PASSWORD=admin \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
  -v "${JAR}:/opt/keycloak/providers/keycloak-magic-link.jar:ro" \
  "${IMAGE}" start-dev >/dev/null

up=
for _ in $(seq 1 80); do curl -fsS "${BASE}/realms/master" >/dev/null 2>&1 && { up=1; break; }; sleep 3; done
[[ -n "${up}" ]] || { echo "FAIL: KC did not start" >&2; docker logs "${KC_NAME}" 2>&1 | tail -60 >&2; exit 1; }

for _ in $(seq 1 10); do kcadm config credentials --server http://localhost:8080 --realm master --user admin --password admin >/dev/null 2>&1 && break; sleep 2; done
kcadm config credentials --server http://localhost:8080 --realm master --user admin --password admin >/dev/null

echo ">> [${KC_VERSION}] realm + SMTP + public PKCE client + user + authenticator browser flow"
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
kcadm create authentication/flows -r "${REALM}" \
  -s alias=magic -s providerId=basic-flow -s topLevel=true -s builtIn=false >/dev/null
kcadm create authentication/flows/magic/executions/execution -r "${REALM}" \
  -b '{"provider":"skycloak-magic-link"}' >/dev/null
EXEC_ID=$(kcadm get authentication/flows/magic/executions -r "${REALM}" \
  --fields id,providerId --format csv --noquotes | grep skycloak-magic-link | head -1 | cut -d, -f1)
[[ -n "${EXEC_ID}" ]] || { echo "FAIL: authenticator not registered" >&2; docker logs "${KC_NAME}" 2>&1 | tail -60 >&2; exit 1; }
kcadm update authentication/flows/magic/executions -r "${REALM}" \
  -b "{\"id\":\"${EXEC_ID}\",\"requirement\":\"REQUIRED\"}" >/dev/null
kcadm create authentication/executions/"${EXEC_ID}"/config -r "${REALM}" \
  -b '{"alias":"mlcfg","config":{"requests-per-minute-per-ip":"0","requests-per-minute-per-email":"0","same-device":"true","token-lifespan-seconds":"600","auto-create-user":"false"}}' >/dev/null
kcadm update realms/"${REALM}" -s browserFlow=magic >/dev/null

FAILED=0
set +e

echo; echo "=== TEST 0 (baseline): a genuine Mode A consume produces a code in this harness ==="
gen_pkce; mh_clear
do_login_request "${WORK}/jar0" "${CHALLENGE}" "s0" "${EMAIL}"
LINK0=$(mh_link) || { echo "FAIL: no Mode A link (baseline)"; docker logs "${KC_NAME}" 2>&1 | tail -40 >&2; exit 1; }
consume "${WORK}/jar0" "${LINK0}"
echo "   baseline consume status: ${CONSUME_STATUS}; code: $([[ -n "${CONSUME_CODE}" ]] && echo present || echo MISSING)"
[[ -n "${CONSUME_CODE}" ]] && echo "   PASS: baseline genuine consume works" || { echo "   FAIL: baseline genuine consume broke (harness/setup, not the fix)"; FAILED=1; }

echo; echo "=== Mint a fresh Mode A (same-device) magic link for the replay test ==="
gen_pkce; mh_clear
do_login_request "${WORK}/jar" "${CHALLENGE}" "s1" "${EMAIL}"
LINK=$(mh_link) || { echo "FAIL: no Mode A link minted"; docker logs "${KC_NAME}" 2>&1 | tail -40 >&2; exit 1; }
KEY=$(echo "${LINK}" | sed -n 's/.*[?&]key=\([^&]*\).*/\1/p')
[[ -n "${KEY}" ]] || { echo "FAIL: could not extract key from Mode A link"; exit 1; }
echo "   Mode A link is an action-token URL; extracted key (len ${#KEY})"
MODEB="${BASE}/realms/${REALM}/skycloak-magic-link/consume?key=${KEY}"

echo; echo "=== ATTACK 1: replay the Mode A token at Mode B's cookieless GET /consume ==="
# An attacker holds the intercepted link but not the victim's device cookie. No cookie jar here.
GET_BODY="${WORK}/getbody"
GET_CODE=$(curl -s -o "${GET_BODY}" -w '%{http_code}' "${MODEB}")
echo "   GET status: ${GET_CODE}"
echo "   GET body (first line): $(head -c 160 "${GET_BODY}")"
if [[ "${GET_CODE}" == "400" ]] && grep -qi 'invalid' "${GET_BODY}" && ! grep -qi 'Continue signing in' "${GET_BODY}"; then
  echo "   PASS: Mode A token rejected at Mode B GET (no confirm page rendered)"
else
  echo "   FAIL: Mode B GET rendered a confirm page or did not reject the Mode A token"; FAILED=1
fi

echo; echo "=== ATTACK 2: replay the Mode A token at Mode B's cookieless POST /consume ==="
POST_HDR="${WORK}/posthdr"; POST_BODY="${WORK}/postbody"
POST_CODE=$(curl -s -D "${POST_HDR}" -o "${POST_BODY}" -w '%{http_code}' -X POST "${MODEB}")
LOC=$(grep -i '^location:' "${POST_HDR}" | tr -d '\r')
CODE_IN_LOC=$(echo "${LOC}" | sed -n 's/.*[?&]code=\([^&]*\).*/\1/p')
echo "   POST status: ${POST_CODE}; Location: ${LOC:-<none>}"
if [[ "${POST_CODE}" != "302" ]] && [[ -z "${CODE_IN_LOC}" ]]; then
  echo "   PASS: Mode A token rejected at Mode B POST (no 302, no authorization code)"
else
  echo "   FAIL: Mode B POST completed a login for a Mode A token (same-device BYPASS)"; FAILED=1
fi

echo; echo "=== Genuine Mode A consume still works (the replay did not burn the link) ==="
consume "${WORK}/jar" "${LINK}"
echo "   genuine consume status: ${CONSUME_STATUS}; code: $([[ -n "${CONSUME_CODE}" ]] && echo present || echo MISSING)"
if [[ -z "${CONSUME_CODE}" ]]; then echo "   [debug] header trace:"; grep -iE '^(HTTP|location)' "${WORK}/c_hdr" | sed 's/^/     /'; fi
if [[ -n "${CONSUME_CODE}" ]]; then
  echo "   PASS: genuine same-device click completed (replay was rejected without burning)"
else
  echo "   FAIL: genuine consume produced no code (replay burned it, or same-device broke)"; FAILED=1
fi

echo
if [[ "${FAILED}" -eq 0 ]]; then
  echo ">> CROSS-PATH REGRESSION PASSED on Keycloak ${KC_VERSION} (same-device bypass closed)"
else
  echo ">> CROSS-PATH FAILURES on Keycloak ${KC_VERSION}" >&2
  docker logs "${KC_NAME}" 2>&1 | tail -100 >&2
  exit 1
fi
