package io.skycloak.keycloak.magiclink;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import org.keycloak.models.KeycloakSession;
import org.keycloak.models.SingleUseObjectProvider;

/**
 * Issues and consumes magic-link tokens against Keycloak's {@link SingleUseObjectProvider}
 * (Infinispan-backed) - the same mechanism Keycloak uses for its own action tokens.
 *
 * <p>Tokens are 32 random bytes hex-encoded (64 chars). The raw token is what travels in the
 * email link; the store keys on {@code "skycloak-magic-link:" + SHA-256(token)} so a heap or
 * cache dump never leaks a usable raw token. Token metadata (userId, clientId, redirectUri)
 * rides in the entry's notes. Tokens are single-use: {@code consume} calls the store's atomic
 * {@code remove}, which the provider guarantees succeeds for at most one caller cluster-wide,
 * so a replay - even on a different node - finds nothing.
 *
 * <p>HONESTY note: in a single-node {@code start-dev} the Infinispan cache is in-heap, so a full
 * COLD restart of a lone node clears in-flight links (exactly like Keycloak's own action tokens).
 * The wins over the previous in-memory map are cluster-wide validity (a link issued on node A is
 * consumable on node B) and survival across a rolling restart. We do NOT claim cold-restart
 * durability for a single node.
 *
 * <p>This is a thin, per-request helper: it holds the {@link KeycloakSession} so it can reach the
 * session-scoped {@code singleUseObjects()} provider.
 */
public final class MagicLinkTokenizer {

    static final String KEY_PREFIX = "skycloak-magic-link:";
    static final String NOTE_USER_ID = "userId";
    static final String NOTE_CLIENT_ID = "clientId";
    static final String NOTE_REDIRECT_URI = "redirectUri";

    private static final SecureRandom RNG = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();
    private static final int TOKEN_BYTES = 32;

    private final KeycloakSession session;

    public MagicLinkTokenizer(KeycloakSession session) {
        this.session = session;
    }

    /** Derives the store key for a raw token. Package-private for unit testing. */
    static String keyFor(String token) {
        return KEY_PREFIX + Hashing.sha256hex(token);
    }

    /**
     * Issues a fresh token bound to the given user / client / redirect URI for the given
     * lifespan and stores it cluster-wide. Returns the raw token (only the hash is stored).
     */
    public String issue(String userId, String clientId, String redirectUri, int lifespanSeconds) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId required");
        }
        if (lifespanSeconds <= 0) {
            throw new IllegalArgumentException("lifespanSeconds must be positive");
        }

        byte[] raw = new byte[TOKEN_BYTES];
        RNG.nextBytes(raw);
        String token = HEX.formatHex(raw);

        Map<String, String> notes = Map.of(
                NOTE_USER_ID, userId,
                NOTE_CLIENT_ID, clientId == null ? "" : clientId,
                NOTE_REDIRECT_URI, redirectUri == null ? "" : redirectUri);

        session.singleUseObjects().put(keyFor(token), lifespanSeconds, notes);
        return token;
    }

    /**
     * Consumes the given raw token via the store's atomic single-use {@code remove}. Returns the
     * bound metadata on success, or empty if the token is unknown, expired, or already consumed.
     */
    public Optional<Entry> consume(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Map<String, String> notes = session.singleUseObjects().remove(keyFor(token));
        if (notes == null) {
            return Optional.empty();
        }
        return Optional.of(new Entry(
                notes.get(NOTE_USER_ID),
                notes.get(NOTE_CLIENT_ID),
                notes.get(NOTE_REDIRECT_URI)));
    }

    /** Snapshot of token metadata carried in the store entry's notes. */
    public static final class Entry {
        private final String userId;
        private final String clientId;
        private final String redirectUri;

        Entry(String userId, String clientId, String redirectUri) {
            this.userId = userId;
            this.clientId = clientId;
            this.redirectUri = redirectUri;
        }

        public String userId() {
            return userId;
        }

        public String clientId() {
            return clientId;
        }

        public String redirectUri() {
            return redirectUri;
        }
    }
}
