---
status: accepted
---

# Magic-link login: self-contained ActionToken, re-applied on a fresh session, same-device by default

The login-flow authenticator (Mode A) captures the user's original authorization-request
parameters (PKCE `code_challenge` + method, scope, state, nonce, redirect_uri) and bakes
them into a self-contained Keycloak ActionToken. When the link is clicked, our
`ActionTokenHandler` starts a fresh authentication session, re-applies those parameters,
and completes the login. The issued authorization code is PKCE-bound because it carries
the re-applied `code_challenge`, so SPA, mobile, and other PKCE clients work correctly.
Verified end-to-end on Keycloak 25.0.6, 26.0.7, and 26.6.3 in the Phase 0 spike: the
happy path issues an access token, and a wrong `code_verifier` is rejected with
`invalid_grant` ("PKCE verification failed").

Login completes in the browser tab that opened the link (a fresh session), not the
original "check your email" tab. We deliberately dropped cross-device continuation, so
there is no in-place completion on the original tab.

Same-device is enforced separately, not by the token. The token is self-contained and
would otherwise work cross-device, so the authenticator sets a device cookie (Secure,
SameSite=Lax, which is required for the top-level GET from an email client to send it)
when it sends the link, and the handler rejects a click whose cookie is absent or
mismatched (verified: cross-device click returns 403, no code). Same-device is the
strongest defense against the trigger-then-click phishing pattern. v0.3.0 enforces it
unconditionally (there is no any-device toggle): any-device would need its own scanner-safe
control, so it is a deferred follow-up rather than a switch that silently weakens the flow.

We use Keycloak's ActionToken subsystem rather than a hand-rolled opaque token so the
security-critical plumbing rides on platform-tested code and stays stable across majors
(the only 25-vs-26 delta found in the spike was one unnecessary helper call, removed
cleanly, so no version shims are needed). The ActionToken is the standard extension point
(Keycloak's own reset-password uses it); our differentiation is the registry overlay
(single-use, revoke), the same-device cookie, and abuse protection, not the token
mechanism.

Note: this supersedes the initial "resume the original session object" framing. PKCE is
preserved by re-applying the captured parameters onto a fresh session, not by reusing the
original session; same-device is a separate cookie-based control. Corrected after the
Phase 0 spike confirmed the mechanism.
