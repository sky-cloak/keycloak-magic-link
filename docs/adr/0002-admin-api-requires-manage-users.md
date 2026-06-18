---
status: accepted
---

# Admin API issuance requires manage-users and is account-takeover-equivalent

The admin REST API that mints magic links (Mode B) is gated on the realm-management
role `manage-users` by default, with the required role configurable per realm. Minting
a magic link produces a working login as the target user, so issuance is
account-takeover-equivalent, not a "read users" operation. We gate on `manage-users`
anyway because it matches Keycloak's nearest native capability (`execute-actions-email`,
which already lets its holder take over an account via a credential-reset link), so we
introduce no new escalation; a holder of `manage-users` could already do this. Strict
deployments can raise the bar by configuring a tighter role (for example `impersonation`
or a dedicated client role).

The consume endpoint is necessarily unauthenticated, since the user clicks it from their
inbox. Its protection is the single-use token, the short expiry, and (for Mode A)
same-device binding, never an admin credential. See [0001](./0001-same-device-actiontoken-resume.md)
for the consume/resume model.
