---
status: accepted
---

# Control plane: revoke via the registry, observability via native events

Pending magic links live in keyed single-use storage (Keycloak's
`SingleUseObjectProvider`). Revocation is a keyed delete using the id returned at
issue time. We deliberately do not expose a live "list pending links" endpoint:
the store cannot enumerate, and a secondary per-user index would drift against TTL
expiry (entries vanish on expiry, the index would not), adding fragile state for a
feature with soft demand.

Outstanding-link visibility is instead derived from the audit event stream
(`issued` minus `consumed` / `revoked` / `expired`). All audit signal flows through
Keycloak's native event system: consume success and failure as `LOGIN` /
`LOGIN_ERROR` user events, and Mode B issuance and revoke as admin events. We use no
bespoke sink and take no dependency on any webhook extension. The
event bus is the integration seam, so any configured listener (jboss-logging,
Skycloak's audit pipeline, a SIEM, or a webhook extension if installed) receives
the events without magic-link being wired to any of them. See
[0001](./0001-same-device-actiontoken-resume.md) for the registry's single-use role.
