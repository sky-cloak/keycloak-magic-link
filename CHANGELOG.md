# Changelog

All notable changes to this project are documented here. The format loosely follows
[Keep a Changelog](https://keepachangelog.com/), and the project adheres to the versioning policy
in [VERSIONING.md](./VERSIONING.md).

## [0.2.1]

### Fixed
- **Per-email rate-limit bypass via email casing.** The `POST /request` per-email window keyed on
  the submitted email verbatim, but Keycloak resolves users case-insensitively, so varying the
  casing or whitespace of one address (`Victim@Example.com`, `VICTIM@EXAMPLE.COM`, ...) minted a
  fresh window per variant and let an attacker amplify magic-link mail to a victim past the
  configured limit. The email is now normalized once (lower-case with `Locale.ROOT` + trim) and the
  same normalized value is used for both the rate-limit key and the user lookup, collapsing every
  variant onto a single window.

## [0.2.0]

### Added
- **Cluster-wide token store.** Magic-link tokens are now stored in Keycloak's
  `SingleUseObjectProvider` (Infinispan), the same mechanism Keycloak uses for its own action
  tokens, replacing the previous per-node in-memory map. A link issued on one replica is now
  consumable on any other, and tokens survive a rolling restart. The atomic `remove` on consume
  provides cluster-wide single-use semantics.
- **`POST /request` rate limiting.** A sliding 60-second window limits requests per client IP
  (default 5/min) and per submitted email (default 3/min), also backed by the cluster-wide store.
  Breaches return `429 Too Many Requests` with a `Retry-After` header. The limit is evaluated
  before any user lookup and keys the email window on the submitted email hash, so a `429` is
  indistinguishable for existing and non-existing accounts and never sends an email. New options:
  `requests-per-minute-per-ip` and `requests-per-minute-per-email`.

### Changed
- `GET /health` now reports `tokenStore`, `requestsPerMinutePerIp`, and
  `requestsPerMinutePerEmail`. The per-node `tokensPending` / `totalIssued` / `totalConsumed`
  counters are removed: the cluster-wide store does not expose live counts.

### Fixed
- Configuration flag prefix corrected to `--spi-realm-restapi-extension-magic-link-...` (the SPI
  is named `realm-restapi-extension`). The previously documented `realm-restapi-provider` prefix
  never took effect.

### Notes
- Cold-restart caveat: in a single-node `start-dev` the Infinispan cache is in-heap, so a full
  cold restart of a lone node clears in-flight links, the same as Keycloak's action tokens. This
  is not durability loss across a cluster or a rolling restart.

## [0.1.0]

MVP. REST-driven passwordless email magic-link sign-in for Keycloak:

- `POST /request` issues a single-use, time-bound token and emails the consume link via the
  realm's SMTP configuration; always returns `202` (no account enumeration).
- `GET /consume` validates the token and completes the standard OIDC authorization-code flow,
  redirecting to the client's registered redirect URI.
- `GET /health` exposes liveness and configuration.
- Tokens are 32 bytes of entropy, stored hashed, single-use, with a configurable lifespan.
- Builds and integration-tests against Keycloak 24, 25, and 26.
