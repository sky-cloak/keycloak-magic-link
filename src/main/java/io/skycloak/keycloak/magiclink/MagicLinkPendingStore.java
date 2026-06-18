package io.skycloak.keycloak.magiclink;

import java.util.Map;

import org.keycloak.models.KeycloakSession;
import org.keycloak.models.SingleUseObjectProvider;

/**
 * Cluster-wide registry of pending (issued but not yet consumed) magic links, backed by Keycloak's
 * {@link SingleUseObjectProvider} (Infinispan). It is the control plane referenced by ADR-0003:
 *
 * <ul>
 *   <li><b>single use</b> - {@link #consume} atomically removes the entry, so a second consume of
 *       the same link finds nothing and fails;</li>
 *   <li><b>revoke</b> (Phase 2) - an admin removing the entry by id has the same effect as a
 *       consume: the next click fails.</li>
 * </ul>
 *
 * Because the link credential is the action token itself (carried in the URL), the registry stores
 * only non-secret metadata keyed by the token's opaque {@code consumeId}; it never stores the token.
 */
final class MagicLinkPendingStore {

    static final String KEY_PREFIX = "skycloak-ml-pending:";
    static final String NOTE_USER = "uid";
    static final String NOTE_CLIENT = "cid";

    private MagicLinkPendingStore() {
    }

    /** Records a pending link so a later {@link #consume} can enforce single use. */
    static void register(KeycloakSession session, String consumeId, long lifespanSeconds,
                         String userId, String clientId) {
        if (consumeId == null) {
            return;
        }
        Map<String, String> notes = Map.of(
                NOTE_USER, userId == null ? "" : userId,
                NOTE_CLIENT, clientId == null ? "" : clientId);
        SingleUseObjectProvider store = session.singleUseObjects();
        store.put(KEY_PREFIX + consumeId, lifespanSeconds, notes);
    }

    /**
     * Atomically consumes a pending link. Returns {@code true} exactly once per registered link;
     * a second call (replay), an expired entry, or a revoked entry returns {@code false}.
     */
    static boolean consume(KeycloakSession session, String consumeId) {
        if (consumeId == null) {
            // No registry entry was created (e.g. same-device disabled path still registers, so a
            // null id is a programming gap); treat as not consumable rather than silently allowing.
            return false;
        }
        SingleUseObjectProvider store = session.singleUseObjects();
        Map<String, String> removed = store.remove(KEY_PREFIX + consumeId);
        return removed != null;
    }
}
