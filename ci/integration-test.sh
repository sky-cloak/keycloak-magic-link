#!/usr/bin/env bash
#
# Boots a real Keycloak container with the built provider mounted, points the realm SMTP
# config at a MailHog sidecar on the same docker network, drives the magic-link request
# endpoint, and verifies the link arrived and completes the OIDC consume step.
#
# Usage: ci/integration-test.sh [keycloak-version]   (default: 26.0.7)
#
set -euo pipefail

KC_VERSION="${1:-26.0.7}"
IMAGE="quay.io/keycloak/keycloak:${KC_VERSION}"
JAR="$(pwd)/target/keycloak-magic-link.jar"
PORT="${KC_PORT:-28084}"
BASE="http://localhost:${PORT}"

SUFFIX="$$-$(date +%s)"
KC_NAME="kc-magic-it-${SUFFIX}"
MAIL_NAME="kc-magic-mail-${SUFFIX}"
NET_NAME="kc-magic-net-${SUFFIX}"
MAIL_HTTP_PORT=$((PORT + 1))

if [[ ! -f "${JAR}" ]]; then
  echo "ERROR: ${JAR} not found - run 'mvn -Dkeycloak.version=${KC_VERSION} package' first." >&2
  exit 1
fi

cleanup() {
  docker rm -f "${KC_NAME}" >/dev/null 2>&1 || true
  docker rm -f "${MAIL_NAME}" >/dev/null 2>&1 || true
  docker network rm "${NET_NAME}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# kcadm.sh executes inside the container, so it always sees the master realm via localhost
# (which avoids the "HTTPS required" guard that trips when calls come from outside loopback).
kcadm() {
  docker exec "${KC_NAME}" /opt/keycloak/bin/kcadm.sh "$@"
}

echo ">> Creating isolated docker network ${NET_NAME}"
docker network create "${NET_NAME}" >/dev/null

echo ">> Starting MailHog (smtp:1025, http:8025) on the network"
docker run -d --name "${MAIL_NAME}" --network "${NET_NAME}" \
  -p "${MAIL_HTTP_PORT}:8025" \
  mailhog/mailhog:latest >/dev/null

# Give MailHog a moment to bind.
for _ in $(seq 1 15); do
  if curl -fsS "http://localhost:${MAIL_HTTP_PORT}/api/v2/messages" >/dev/null 2>&1; then break; fi
  sleep 1
done

echo ">> Starting ${IMAGE} with provider mounted"
docker run -d --name "${KC_NAME}" --network "${NET_NAME}" -p "${PORT}:8080" \
  -e KEYCLOAK_ADMIN=admin -e KEYCLOAK_ADMIN_PASSWORD=admin \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
  -v "${JAR}:/opt/keycloak/providers/keycloak-magic-link.jar:ro" \
  "${IMAGE}" start-dev >/dev/null

echo ">> Waiting for Keycloak to come up"
up=
for _ in $(seq 1 80); do
  if curl -fsS "${BASE}/realms/master" >/dev/null 2>&1; then up=1; break; fi
  sleep 3
done
if [[ -z "${up}" ]]; then
  echo "ERROR: Keycloak did not start in time" >&2
  docker logs "${KC_NAME}" 2>&1 | tail -80 >&2
  exit 1
fi

echo ">> Health endpoint (public)"
HEALTH=$(curl -fsS "${BASE}/realms/master/magic-link/health")
echo "   ${HEALTH}"
echo "${HEALTH}" | grep -q '"active":true' \
  || { echo "FAIL: provider not active - it did not load" >&2; docker logs "${KC_NAME}" 2>&1 | tail -40 >&2; exit 1; }

echo ">> Authenticating kcadm.sh inside the container"
# Retry briefly - the temporary admin user is created slightly after the HTTP listener opens.
for i in $(seq 1 10); do
  if kcadm config credentials --server http://localhost:8080 --realm master \
      --user admin --password admin >/dev/null 2>&1; then
    break
  fi
  sleep 2
done
kcadm config credentials --server http://localhost:8080 --realm master --user admin --password admin >/dev/null

echo ">> Relaxing master realm sslRequired so the test client can reach the token endpoint over HTTP"
kcadm update realms/master -s sslRequired=NONE >/dev/null

echo ">> Pointing master realm SMTP at MailHog (${MAIL_NAME}:1025)"
kcadm update realms/master \
  -s 'smtpServer.host='"${MAIL_NAME}" \
  -s 'smtpServer.port=1025' \
  -s 'smtpServer.from=noreply@example.test' \
  -s 'smtpServer.fromDisplayName=Skycloak Magic Link' \
  -s 'smtpServer.auth=false' \
  -s 'smtpServer.ssl=false' \
  -s 'smtpServer.starttls=false' >/dev/null

echo ">> Configuring the account client (enable direct access, add redirect URI)"
ACCOUNT_CLIENT_ID=$(kcadm get clients -r master -q clientId=account --fields id --format csv --noquotes | tail -1)
[[ -n "${ACCOUNT_CLIENT_ID}" ]] || { echo "FAIL: could not find account client" >&2; exit 1; }
kcadm update clients/"${ACCOUNT_CLIENT_ID}" -r master \
  -s 'redirectUris=["http://localhost/back","/realms/master/account/*"]' \
  -s 'directAccessGrantsEnabled=true' >/dev/null

echo ">> Giving the admin user an email address"
ADMIN_USER_ID=$(kcadm get users -r master -q username=admin --fields id --format csv --noquotes | tail -1)
[[ -n "${ADMIN_USER_ID}" ]] || { echo "FAIL: could not find admin user" >&2; exit 1; }
kcadm update users/"${ADMIN_USER_ID}" -r master \
  -s 'email=admin@example.test' -s 'emailVerified=true' >/dev/null

echo ">> Requesting a magic link"
REQ_STATUS=$(curl -fsS -o /tmp/magic-resp.json -w '%{http_code}' \
  -X POST "${BASE}/realms/master/magic-link/request" \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@example.test","clientId":"account","redirectUri":"http://localhost/back"}')
echo "   HTTP ${REQ_STATUS} - $(cat /tmp/magic-resp.json)"
[[ "${REQ_STATUS}" == "202" ]] || { echo "FAIL: expected 202 from /request, got ${REQ_STATUS}" >&2; docker logs "${KC_NAME}" 2>&1 | tail -60 >&2; exit 1; }

echo ">> Waiting for the email to arrive in MailHog"
LINK=""
for _ in $(seq 1 30); do
  MSGS=$(curl -fsS "http://localhost:${MAIL_HTTP_PORT}/api/v2/messages" || echo '{}')
  COUNT=$(echo "${MSGS}" | sed -n 's/.*"total":\([0-9]*\).*/\1/p')
  if [[ "${COUNT:-0}" -gt 0 ]]; then
    # Quoted-printable bodies wrap long lines with "=\n"; reverse that before extracting.
    LINK=$(echo "${MSGS}" | perl -0pe 's/=(?:\r?\n)//g' \
      | grep -oE 'http[^"\\ ]+/realms/master/magic-link/consume[^"\\ ]+' | head -1)
    if [[ -n "${LINK}" ]]; then break; fi
  fi
  sleep 1
done
if [[ -z "${LINK}" ]]; then
  echo "FAIL: no magic-link email arrived" >&2
  echo "---- mailhog messages ----" >&2
  curl -fsS "http://localhost:${MAIL_HTTP_PORT}/api/v2/messages" >&2 || true
  echo "---- keycloak logs ----" >&2
  docker logs "${KC_NAME}" 2>&1 | tail -80 >&2
  exit 1
fi
echo "   ${LINK}"

echo ">> Consuming the magic link"
CONSUME_RESP=$(curl -sS -o /dev/null -D - "${LINK}")
STATUS_LINE=$(echo "${CONSUME_RESP}" | head -1 | tr -d '\r')
LOCATION=$(echo "${CONSUME_RESP}" | grep -i '^location:' | head -1 | tr -d '\r')
echo "   ${STATUS_LINE}"
echo "   ${LOCATION}"
if echo "${STATUS_LINE}" | grep -qE 'HTTP/[0-9.]+ 30[0-9]' && echo "${LOCATION}" | grep -q 'http://localhost/back'; then
  echo ">> PASS: magic link signed the user in and redirected to the app (Keycloak ${KC_VERSION})"
else
  echo "FAIL: consume did not produce a 30x to the redirect URI" >&2
  echo "${CONSUME_RESP}" >&2
  echo "---- keycloak logs ----" >&2
  docker logs "${KC_NAME}" 2>&1 | tail -120 >&2
  exit 1
fi

echo ">> Best-effort: second consume of the same token should now fail"
SECOND=$(curl -sS -o /tmp/magic-second.json -w '%{http_code}' "${LINK}")
echo "   second consume HTTP ${SECOND} - $(cat /tmp/magic-second.json)"
if [[ "${SECOND}" == "401" ]]; then
  echo "   single-use enforcement confirmed"
else
  echo "   (informational) second consume returned ${SECOND}; primary 302 assertion already passed"
fi
