# Keycloak Magic Link

Drop-in passwordless email sign-in for Keycloak. Send a one-time link to a user's inbox;
they click it; Keycloak signs them in and redirects back to your app with a standard OIDC
authorization code. No password, no licensing strings, no extra service to run.

Keycloak has shipped without first-class magic-link login for years. There are dozens of
half-finished community plugins and a paid alternative whose license blocks straightforward
production use. This extension closes the gap: one small jar, MIT-friendly Apache 2.0 license,
works with any OIDC client you already have.

- **Two entry paths** - REST trigger (`POST /request`) for API-driven UX, click-back endpoint
  (`GET /consume`) that completes the OIDC flow.
- **Standard OIDC code flow** - on consume, Keycloak issues an authorization code against your
  existing client and redirects to the registered redirect URI. Your app exchanges the code
  for tokens the normal way.
- **Single-use, time-bound tokens** - 32 bytes of entropy, stored hashed, expire on a
  configurable lifespan (default 10 minutes), invalidated on first consume.
- **No account enumeration** - the request endpoint returns `202` whether or not the email
  matches a user; nothing leaks through timing or response shape.
- **Email through your realm SMTP** - reuses Keycloak's configured outbound mail. No new
  credentials, no second deliverability story.
- **Zero runtime dependencies** - one jar, no database changes, no extra services.

Works with **Keycloak 24, 25, and 26** (Quarkus distribution).

## Install

### Option A - download the jar

1. Grab `keycloak-magic-link.jar` from the [latest release](https://github.com/sky-cloak/keycloak-magic-link/releases).
2. Copy it into Keycloak's providers directory:
   ```bash
   cp keycloak-magic-link.jar /opt/keycloak/providers/
   ```
3. Restart Keycloak (the provider is picked up on the next build/start).

### Option B - build from source

```bash
mvn package
cp target/keycloak-magic-link.jar /opt/keycloak/providers/
```

To build against a specific server version: `mvn -Dkeycloak.version=25.0.6 package`.

### Option C - try it with Docker

```bash
mvn package && docker compose up
```

Keycloak starts on <http://localhost:8080> (admin / admin) with the provider installed and
MailHog catching outbound mail on <http://localhost:8025>.

## Enable it

Nothing to wire in the admin console; the extension is active as soon as the jar is loaded.
What you do need:

1. **Realm SMTP configured.** Magic-link emails go out through the realm's existing mail
   configuration (Realm settings -> Email). If SMTP is not set, the request endpoint accepts
   the request but logs a warning - no email is sent.
2. **A client with a valid `redirectUri`.** The consume endpoint validates the requested
   redirect URI against the client's allowed redirect URIs exactly the way the rest of
   Keycloak does. Unknown or unregistered URIs are rejected.
3. **Users with an `email` attribute.** The request endpoint looks users up by email. Set
   the field on the user; verification is not required by this extension but verifying
   email ownership separately is recommended.

## Endpoints

Mounted under `/realms/{realm}/magic-link`:

| Method & path | Auth | Returns |
|---|---|---|
| `GET /health` | public | `{status, name, version, active, tokenLifespanSeconds, tokensPending, totalIssued, totalConsumed}` |
| `POST /request` | public | `202 Accepted` always (no account enumeration) |
| `GET /consume` | public, single-use token | `302` to the bound redirect URI with an OIDC `code` |

### `POST /request`

```bash
curl -X POST "$KC_URL/realms/$REALM/magic-link/request" \
  -H 'Content-Type: application/json' \
  -d '{
        "email":       "alice@example.com",
        "clientId":    "my-app",
        "redirectUri": "https://app.example.com/auth/callback"
      }'
```

Response is always `202 {"status":"accepted"}`. If the email matches an enabled user and the
client/redirect-URI pair is valid, a magic link is generated and sent through the realm SMTP.
If anything is off (no such user, disabled user, unknown client, redirect URI not registered)
the response is identical - the caller cannot probe for account existence.

### `GET /consume`

The link delivered by email points at this endpoint:

```
GET /realms/{realm}/magic-link/consume?token=...&clientId=my-app&redirectUri=https%3A%2F%2Fapp.example.com%2Fauth%2Fcallback
```

The token is the source of truth: `clientId` and `redirectUri` query params are optional,
but if supplied they must match the values bound at issue time exactly. A successful consume
returns:

```
HTTP/1.1 302 Found
Location: https://app.example.com/auth/callback?session_state=...&iss=...&code=...
```

Your app exchanges `code` for tokens at the realm's token endpoint as it normally would.

A second consume of the same token returns `401 invalid_token`. An expired token returns the
same response shape.

## Configuration

All optional, read once at startup. Set via CLI flags or the matching `KC_SPI_*` environment
variables.

| Option | Default | Description |
|---|---|---|
| `--spi-realm-restapi-provider-magic-link-token-lifespan-seconds` | `600` | how long a magic-link token is valid for, in seconds |
| `--spi-realm-restapi-provider-magic-link-from-email-override` | _(realm SMTP `from`)_ | overrides the `From:` address on outbound magic-link emails |
| `--spi-realm-restapi-provider-magic-link-subject-template` | `Your sign-in link` | subject line on the email |
| `--spi-realm-restapi-provider-magic-link-body-template` | _(see source)_ | email body template; `{link}` is substituted with the consume URL. If the placeholder is absent the link is appended. |

Example - tighter 5-minute lifespan with a branded subject:

```bash
bin/kc.sh start \
  --spi-realm-restapi-provider-magic-link-token-lifespan-seconds=300 \
  --spi-realm-restapi-provider-magic-link-subject-template="Sign in to Acme"
```

## Notes & limits

- **Token store is per-node and in-memory.** Tokens issued by one Keycloak replica cannot be
  consumed by a different replica. Run a single replica, or pin requests to one node with a
  sticky session / hash route. A cluster-aware store is a v2 concern.
- **Tokens are single-use** and invalidated on the first consume; replay attempts fail with
  `401 invalid_token`.
- **The browser-flow Authenticator is a fast-follow.** This MVP only ships the REST path. The
  intended next addition is a `skycloak-magic-link` Authenticator factory you can drop into a
  copy of the browser flow to give end users a "send me a magic link" form on the login page.
- **Email deliverability is the realm's responsibility.** The extension reuses
  `realm.getSmtpConfig()` and Keycloak's `EmailSenderProvider`. If SMTP delivery fails the
  failure is logged and the user sees nothing - this matches Keycloak's behavior elsewhere.
- **No account enumeration.** `POST /request` is constant-shape regardless of input - keep
  it that way if you ever fork this code.

## Build & test

```bash
mvn package                              # build + unit tests
ci/integration-test.sh 26.0.7            # boot a real Keycloak + MailHog and run an end-to-end test
```

CI builds and runs the integration test against Keycloak 24, 25, and 26 on every push.

## License

Apache License 2.0 - see [LICENSE](LICENSE).
