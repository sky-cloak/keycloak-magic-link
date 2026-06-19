---
status: accepted
---

# Secure-by-default consume and issuance behaviors

Several magic-link defaults are deliberately strict. Each closes a specific threat, and
none should be relaxed for convenience without understanding what it gives up. Recorded
here because most of them look like friction or redundancy to a future reader and invite
a well-meaning "simplification" that reopens a hole.

- **Identical output whether or not the account exists.** No "account not found"
  message; the unknown-email path renders the same "check your email" page and sends
  nothing. Keeps the login box from becoming an account-enumeration oracle.
- **Scanner-safe single-use consume (revised across Phase 1 and Phase 2).** Under
  same-device (the default), consume is single-step and scanner-safe by construction: the
  same-device cookie check runs before the single-use burn, so a scanner that prefetches
  the link has no device cookie, fails the check, and returns a 403 without burning the
  registry entry; the genuine same-browser click then burns it once and completes. Mode A
  always carries a device cookie in v0.3.0 (any-device is deferred, see 0001), so its
  consume is always this single-step path. The no-cookie path (Mode B admin-issued links,
  which are cross-device) instead uses a two-step confirm: GET shows a "Sign in?" page
  without burning, POST burns and completes, so a scanner's prefetch GET is harmless. That
  two-step lives on the resource's own GET/POST `/skycloak-magic-link/consume`, not on
  Keycloak's action-token endpoint, because that endpoint is GET-only (a POST to it 404s).
  When any-device Mode A is eventually added it must route through this two-step path (or
  ship the confirmation code), never the single-step action-token handler.
- **Cross-path guard (security-critical).** The cookieless resource `/consume` must reject
  any token that carries a device nonce (a same-device Mode A token). Without this, an
  intercepted Mode A link could be replayed against the cookieless endpoint and bypass
  same-device entirely. Same-device tokens are consumable only via the action-token handler
  that checks the cookie; do not loosen this.
- **Single-use, short-lived.** Atomic remove-on-consume in the registry, 10-minute
  default lifespan (per-flow configurable). Standard replay and exposure-window control.
- **Issuance rate-limiting.** The authenticator send action is limited per client IP and
  per submitted email; breaches are indistinguishable from the unknown-account path.
  Prevents mail-bombing a victim and preserves non-enumeration. Mode B also caps per
  target email.
- **Successful consume sets `emailVerified=true`.** Clicking a link delivered to an
  address is proof of control of that address, the same basis as the verify-email action.

Same-device binding, the other core default, is covered in
[0001](./0001-same-device-actiontoken-resume.md).
