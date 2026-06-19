# Keycloak Magic Link

![CI](https://github.com/sky-cloak/keycloak-magic-link/actions/workflows/ci.yml/badge.svg)
![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)
![Keycloak](https://img.shields.io/badge/Keycloak-25%20%7C%2026-blue.svg)

> **Running Keycloak in production?** [Skycloak](https://skycloak.io) is managed Keycloak hosting
> with automated upgrades, monitoring, backups, and expert support, so your team ships features
> instead of operating identity infrastructure.

Passwordless email magic-link sign-in for Keycloak. A user receives a one-time link, clicks
it, and Keycloak signs them in with a standard OIDC authorization code. One small jar,
Apache 2.0, no extra service to run.

It works two ways:

- **Login-flow authenticator (self-service).** Drop a "Magic Link (Skycloak)" step into a
  browser flow. The user enters their email on the Keycloak login page, gets a link, and
  clicking it in the same browser completes their original sign-in.
- **Admin API (programmatic).** A backend with the right role mints a link for a user over
  REST, to email or deliver however you like.

Both paths are secure by default: links are single-use, short-lived, PKCE-correct,
non-enumerable, rate-limited, and resistant to email link-scanners.

## Why this one

Keycloak has shipped without first-class magic-link login for years. This extension focuses
on getting the security defaults right rather than on breadth: same-device binding by
default, scanner-safe consume, revocable admin links, and native audit events, under a
permissive Apache 2.0 license. If you need cross-device continuation, one-time codes, or
other variants, other community extensions cover those; this one is the secure, operable
baseline.

## Managed option

Prefer not to self-host? [Skycloak](https://skycloak.io) is **managed, hosted Keycloak**: automated
version upgrades (this extension included), monitoring and alerting, backups, custom domains, and
enterprise Keycloak support. It is upstream Keycloak underneath, so there is no lock-in and you can
migrate an existing realm in. This extension is built and maintained by the Skycloak team.

Passwordless and magic-link login, ready to switch on at [skycloak.io](https://skycloak.io).

## Compatibility

Keycloak **25 and 26** (Quarkus distribution), tested against 25.0.6, 26.0.7, and the
current 26.6.x on every push.

## Install

### Download the jar

1. Grab `keycloak-magic-link.jar` from the [latest release](https://github.com/sky-cloak/keycloak-magic-link/releases).
2. Copy it into Keycloak's providers directory and restart:
   ```bash
   cp keycloak-magic-link.jar /opt/keycloak/providers/
   ```

### Build from source

```bash
mvn package
cp target/keycloak-magic-link.jar /opt/keycloak/providers/
```

Build against a specific server: `mvn -Dkeycloak.version=26.6.3 package`.

## Try it locally

```bash
mvn package && docker compose up
```

This boots Keycloak with the provider, MailHog to catch the emails, and a one-shot `demo-setup`
container that relaxes `sslRequired` (so plain `http://localhost` works, no "HTTPS required") and
provisions a ready **`demo`** realm with a Magic Link login. Wait for `demo-setup` to log
**`DEMO READY`**, then:

1. Open <http://localhost:8080/realms/demo/account> and enter `alice@example.test`.
2. Open the link from the **MailHog inbox** (<http://localhost:8025>) in the same browser. You are
   signed in.

Security check: open that same link in a different browser (or incognito). It is rejected
("open it in the same browser") - that is same-device binding.

The admin console is at <http://localhost:8080> (admin / admin). To wire magic-link into your own
realm, see [Mode A](#mode-a-login-flow-authenticator); for the issuance API, see
[Mode B](#mode-b-admin-issuance-api).

## Prerequisites

1. **Realm SMTP** (Realm settings -> Email) for outbound links.
2. **A client with a registered `redirectUri`.** Redirect URIs are validated against the
   client exactly as elsewhere in Keycloak.
3. **Users with an `email`.** Magic-link resolves users by email.

## Mode A: login-flow authenticator

Add the authenticator to a browser flow:

1. Authentication -> Flows -> duplicate the **browser** flow.
2. In the copy, add an execution -> **Magic Link (Skycloak)** -> set it **Required** (drop
   the username/password step for passwordless-only).
3. Bind it: the flow's Action menu -> **Bind flow** -> Browser flow, or override it per
   client under the client's Advanced settings.

The user now sees an email field on the login page. They submit it, get a link, and clicking
it **in the same browser** completes the original authorization request, so the issued code
keeps its PKCE `code_challenge`, scopes, nonce, and redirect.

### Configuration (per flow)

Set these on the authenticator execution (the gear icon) in the admin console:

| Setting | Default | Description |
|---|---|---|
| Token lifespan (seconds) | `600` | how long a link is valid |
| Auto-create user | `off` | create a user for an unknown email on first request |
| Requests/min/IP | `5` | per-client-IP rate limit (`0` disables) |
| Requests/min/email | `3` | per-email rate limit (`0` disables) |

An auto-created user with only an email may be prompted to complete their profile on first
sign-in, per the realm's required actions.

## Conditional flows (password or magic link)

The authenticator composes with Keycloak's stock flow building blocks, so the common "password or
magic link" UX needs no extra code, just flow configuration (Authentication -> Flows):

- **Offer both, user chooses.** Put Password and **Magic Link (Skycloak)** as **Alternative**
  executions in the same sub-flow. Keycloak shows one, with a "Try another way" link to switch.
- **Password if the user has one, else send a link.** Identify the user first (Username Form), then
  a conditional sub-flow of **Condition - user configured** + Password: it runs only for users who
  have a password, and everyone else falls through to **Magic Link (Skycloak)**.
- **Route by attribute or role.** Gate the magic-link sub-flow with **Condition - User Attribute**
  (e.g. `prefer-magic-link=true`) or **Condition - User Role**.

Heads up: any identity-first flow that shows different options per user reveals whether an account
exists and what credentials it has. The standalone magic-link step is non-enumerable by design; the
conditional patterns trade that away, so choose deliberately.

## Mode B: admin issuance API

Mounted at `/realms/{realm}/skycloak-magic-link`. Issuance requires the realm-management role
**`manage-users`** (override per realm with the `skycloak-magic-link-issue-role` attribute).
Minting a link logs a user in, so treat the endpoint as account-takeover-equivalent.

| Method & path | Auth | Purpose |
|---|---|---|
| `POST /realms/{realm}/skycloak-magic-link` | `manage-users` | issue a link |
| `DELETE /realms/{realm}/skycloak-magic-link/{id}` | `manage-users` | revoke a pending link |
| `GET /realms/{realm}/skycloak-magic-link/health` | public | liveness |

```bash
curl -X POST "$KC_URL/realms/$REALM/skycloak-magic-link" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
        "email":       "alice@example.com",
        "clientId":    "my-app",
        "redirectUri": "https://app.example.com/auth/callback",
        "send":        true
      }'
```

Returns `201 {"id": "...", "expiresAt": ...}`. With `send:false` the response also includes
`link` for you to deliver yourself (SMS, your own templated email, ...); the link is never
written to logs. Revoke with `DELETE .../{id}` using the returned `id`.

Admin-issued links are cross-device (no same-device cookie), so they use a two-step confirm:
the emailed link is a `GET` that shows a "Sign in?" page without consuming, and only the human
`POST` completes. Email security scanners that follow GET links therefore cannot burn the link.

## Customizing copy and email

Every on-screen string and the email are Keycloak message keys (defaults in the bundled
`messages_en.properties`), so you can reword or translate them without touching code:

- **Per realm, no rebuild:** Realm settings -> Localization -> enable, then add a key and value,
  e.g. `magicLinkFormTitle`, `magicLinkSentInstruction`, `magicLinkEmailSubject`. Add the same keys
  under another locale to translate.
- **Via a theme:** override the keys in your login/email theme's message bundle, or replace the
  `skycloak-magic-link-*.ftl` templates entirely for full control of the markup.

Key prefixes: `magicLinkForm*` / `magicLinkSent*` / `magicLinkConfirm*` (pages) and
`magicLinkEmail*` (email).

## Security model

- **Single-use, short-lived.** Links are one-time and expire on a configurable lifespan
  (default 10 minutes), tracked cluster-wide in Keycloak's `SingleUseObjectProvider`.
- **Same-device (Mode A).** The link completes only in the browser that requested it (a
  `SKYCLOAK_MAGIC_DEVICE` cookie), so an intercepted or forwarded link is useless
  elsewhere. v0.3.0 enforces this unconditionally; any-device is a deferred follow-up.
- **Scanner-safe.** Under same-device the device-cookie check precedes the burn, so a
  scanner's cookieless prefetch fails without consuming. Cookieless admin-issued links use
  the two-step confirm instead.
- **PKCE-correct.** Mode A completes the user's original authorization request, so codes stay
  bound to the client's PKCE challenge.
- **No account enumeration.** The authenticator shows the same "check your email" page whether
  or not the address exists, sends only for a real enabled user, and rate-limit breaches look
  identical.
- **Rate-limited.** Per-IP and per-email sliding windows, enforced cluster-wide.
- **Audited.** Issue and revoke emit Keycloak admin events; consume emits `LOGIN` and
  `LOGIN_ERROR` user events. No event carries the link or token.
- **Email-verified on use.** A successful sign-in sets `emailVerified=true` (clicking a
  delivered link proves control of the address).

## Build & test

```bash
mvn package                                   # build + unit tests
ci/magic-link-authenticator-test.sh 26.6.3    # Mode A end-to-end (boots a real Keycloak + MailHog)
ci/magic-link-admin-test.sh 26.6.3            # Mode B admin API
ci/magic-link-crosspath-test.sh 26.6.3        # same-device bypass regression
```

CI builds and runs the full suite against Keycloak 25.0.6, 26.0.7, and 26.6.3 on every push.

## Naming

Server-global identifiers are prefixed `skycloak-` so they cannot collide with other
extensions on the same server: the authenticator id, the action-token type, and the
realm-resource id and path (`/realms/{realm}/skycloak-magic-link`). The Maven coordinates are
`io.skycloak.keycloak`.

## License

Apache License 2.0 - see [LICENSE](LICENSE).
