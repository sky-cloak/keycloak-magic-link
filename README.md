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
- **Cluster-wide token store** - tokens live in Keycloak's `SingleUseObjectProvider`
  (Infinispan), the same store Keycloak uses for its own action tokens. A link issued on one
  replica is consumable on any other, and tokens survive a rolling restart.
- **Request rate limiting** - `POST /request` is throttled per client IP and per email on a
  sliding 60-second window (also cluster-wide). Breaches return `429` with `Retry-After`.
- **No account enumeration** - the request endpoint returns `202` whether or not the email
  matches a user; rate limiting runs before user lookup and keys on the submitted email hash, so
  even a `429` looks identical for existing and non-existing accounts. Nothing leaks through
  timing or response shape.
- **Email through your realm SMTP** - reuses Keycloak's configured outbound mail. No new
  credentials, no second deliverability story.
- **No new runtime dependencies** - one jar, no database changes, no extra services; it reuses
  Keycloak's own Infinispan caches.

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
| `GET /health` | public | `{status, name, version, active, tokenStore, tokenLifespanSeconds, requestsPerMinutePerIp, requestsPerMinutePerEmail}` |
| `POST /request` | public | `202 Accepted` (no account enumeration), or `429 Too Many Requests` with `Retry-After` when rate-limited |
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

Response is `202 {"status":"accepted"}`. If the email matches an enabled user and the
client/redirect-URI pair is valid, a magic link is generated and sent through the realm SMTP.
If anything is off (no such user, disabled user, unknown client, redirect URI not registered)
the response is identical - the caller cannot probe for account existence.

#### Rate limiting

`POST /request` is throttled on two independent sliding 60-second windows: one per client IP and
one per submitted email. Limits default to **5 requests/minute/IP** and **3 requests/minute/email**
and are configurable (see below). When either window is exceeded the endpoint returns:

```
HTTP/1.1 429 Too Many Requests
Retry-After: 42
Content-Type: application/json

{"error":"rate_limited","error_description":"too many magic-link requests; retry later","retry_after":42}
```

`Retry-After` (seconds) is how long until the oldest in-window request ages out, leaving room for
one more.

The limit is evaluated **before** any user lookup and the email window is keyed by the SHA-256 of
the *submitted* email regardless of whether it maps to a user. A `429` therefore looks identical
whether or not the account exists: rate limiting never becomes an enumeration oracle. A throttled
request sends no email.

Because the windows live in the same cluster-wide store as the tokens, the limit is enforced
across all replicas, not per node. The window is only 60 seconds, so the single-node cold-restart
caveat noted below for tokens does not meaningfully apply here.

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

The SPI is named `realm-restapi-extension` and this provider's id is `magic-link`, so every flag
is prefixed `--spi-realm-restapi-extension-magic-link-`.

| Option | Default | Description |
|---|---|---|
| `--spi-realm-restapi-extension-magic-link-token-lifespan-seconds` | `600` | how long a magic-link token is valid for, in seconds |
| `--spi-realm-restapi-extension-magic-link-from-email-override` | _(realm SMTP `from`)_ | overrides the `From:` address on outbound magic-link emails |
| `--spi-realm-restapi-extension-magic-link-subject-template` | `Your sign-in link` | subject line on the email |
| `--spi-realm-restapi-extension-magic-link-body-template` | _(see source)_ | email body template; `{link}` is substituted with the consume URL. If the placeholder is absent the link is appended. |
| `--spi-realm-restapi-extension-magic-link-requests-per-minute-per-ip` | `5` | max `POST /request` calls per minute from one client IP. `0` disables the per-IP limit. |
| `--spi-realm-restapi-extension-magic-link-requests-per-minute-per-email` | `3` | max `POST /request` calls per minute for one submitted email. `0` disables the per-email limit. |

Example - tighter 5-minute lifespan, a branded subject, and a stricter per-email cap:

```bash
bin/kc.sh start \
  --spi-realm-restapi-extension-magic-link-token-lifespan-seconds=300 \
  --spi-realm-restapi-extension-magic-link-subject-template="Sign in to Acme" \
  --spi-realm-restapi-extension-magic-link-requests-per-minute-per-email=2
```

## Notes & limits

- **Token store is cluster-wide via `SingleUseObjectProvider` (Infinispan).** Tokens live in the
  same store Keycloak uses for its own action tokens, so a link issued on one replica is
  consumable on any other and tokens survive a rolling restart. The atomic `remove` on consume
  is the single-use primitive: even concurrent consumes across nodes can succeed at most once.
- **Cold-restart caveat (single node only).** In a single-node `start-dev` the Infinispan cache
  is in-heap, so a full *cold* restart of a lone node clears in-flight links - exactly like
  Keycloak's own action tokens. This is not durability loss across a cluster or a rolling
  restart; it only affects a single isolated node going fully down. The 60-second rate-limit
  windows are short enough that this caveat does not meaningfully affect them.
- **Tokens are single-use** and invalidated on the first consume; replay attempts fail with
  `401 invalid_token`.
- **Request rate limiting is on by default** (5/min/IP, 3/min/email) and enforced cluster-wide.
  Set the per-IP or per-email limit to `0` to disable that dimension. See
  [Rate limiting](#rate-limiting).
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

Unit tests cover the pure logic (sliding-window prune/count/limit and token-key derivation). The
integration test boots a real Keycloak with the provider mounted and asserts the happy-path login,
single-use enforcement, and the rate-limit path (a burst trips `429` with `Retry-After`, a clean
request still returns `202`, and throttled calls send no email). CI builds and runs it against
Keycloak 24, 25, and 26 on every push.

## License

Apache License 2.0 - see [LICENSE](LICENSE).
