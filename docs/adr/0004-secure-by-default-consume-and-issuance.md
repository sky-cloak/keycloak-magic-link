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
- **Scanner-safe single-use consume (revised in Phase 1).** Under same-device (the
  default), consume is single-step and scanner-safe by construction: the same-device
  cookie check runs before the single-use burn, so an email security scanner (Safe Links,
  Mimecast, Proofpoint) that prefetches the link has no device cookie, fails the check,
  and returns a 403 without burning the registry entry; the genuine same-browser click
  then burns it once and completes. No confirm page is needed while same-device is on. The
  originally-planned two-step confirm page (GET shows a "Sign in?" page, POST burns and
  completes) proved both redundant here and brittle to render from an action-token
  handler, so it was dropped as the default. It is retained on paper as the compensating
  control for the opt-in any-device mode, where there is no device cookie to gate on and a
  single-step link would otherwise be scanner-burnable: any-device must not be enabled
  until that control (or the Q6 confirmation code) ships.
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
