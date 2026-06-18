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
- **Two-step consume.** `GET` renders a confirm page and does not burn the token; `POST`
  completes the login. This survives email security products (Safe Links, Mimecast,
  Proofpoint) that pre-fetch links and would otherwise consume a single-use link before
  the human clicks. Do not collapse it to one-click `GET`. Implementation note (from the
  Phase 0 spike): Keycloak's action-token endpoint is a `GET` that validates, completes,
  and burns single-use in one shot, so the handler must render the confirm form on `GET`
  and defer the burn and completion to the `POST`, re-validating without consuming on the
  `GET`. Feasible (reset-credentials renders forms from action tokens) but real Phase 1
  work, not free.
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
