#!/usr/bin/env bash
# One-shot provisioning for the local `docker compose up` trial. Runs kcadm over Keycloak's
# loopback interface (the only address exempt from the realm sslRequired guard), so it relaxes
# sslRequired for HTTP-on-localhost testing and leaves a ready-to-try `demo` realm:
#   - sslRequired=NONE on master (admin console over http) and demo (login over http)
#   - SMTP pointed at the MailHog sidecar
#   - user alice@example.test
#   - a browser flow whose single required step is the Magic Link (Skycloak) authenticator
# Idempotent: re-running `docker compose up` is safe. sslRequired is relaxed first, so the HTTP
# fix holds even if later steps are skipped.
set -uo pipefail
KC=/opt/keycloak/bin/kcadm.sh

echo ">> waiting for Keycloak"
until $KC config credentials --server http://localhost:8080 --realm master --user admin --password admin >/dev/null 2>&1; do sleep 3; done

echo ">> relaxing sslRequired (master) so http://localhost works"
$KC update realms/master -s sslRequired=NONE

echo ">> demo realm (sslRequired=NONE) + SMTP + user alice"
$KC create realms -s realm=demo -s enabled=true -s sslRequired=NONE 2>/dev/null || $KC update realms/demo -s sslRequired=NONE
$KC update realms/demo -s smtpServer.host=mailhog -s smtpServer.port=1025 \
  -s smtpServer.from=noreply@example.test -s smtpServer.auth=false -s smtpServer.ssl=false -s smtpServer.starttls=false
$KC create users -r demo -s username=alice -s email=alice@example.test \
  -s firstName=Alice -s lastName=Example -s enabled=true 2>/dev/null || true

echo ">> magic-link browser flow (bound)"
$KC create authentication/flows -r demo -s alias=magic -s providerId=basic-flow -s topLevel=true -s builtIn=false 2>/dev/null || true
$KC create authentication/flows/magic/executions/execution -r demo -b '{"provider":"skycloak-magic-link"}' 2>/dev/null || true
EXEC=$($KC get authentication/flows/magic/executions -r demo --fields id,providerId --format csv --noquotes \
  | grep skycloak-magic-link | head -1 | cut -d, -f1)
if [ -n "${EXEC}" ]; then
  $KC update authentication/flows/magic/executions -r demo -b "{\"id\":\"${EXEC}\",\"requirement\":\"REQUIRED\"}"
  $KC create authentication/executions/"${EXEC}"/config -r demo \
    -b '{"alias":"mlcfg","config":{"requests-per-minute-per-ip":"0","requests-per-minute-per-email":"0","token-lifespan-seconds":"600","auto-create-user":"false"}}' 2>/dev/null || true
  $KC update realms/demo -s browserFlow=magic
fi

echo ">> DEMO READY"
echo "   Sign in: http://localhost:8080/realms/demo/account  (user alice@example.test)"
echo "   Inbox:   http://localhost:8025"
echo "   Admin:   http://localhost:8080  (admin / admin)"
