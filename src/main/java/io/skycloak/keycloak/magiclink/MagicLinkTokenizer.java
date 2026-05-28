package io.skycloak.keycloak.magiclink;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Issues and consumes magic-link tokens.
 *
 * <p>Tokens are 32 random bytes hex-encoded (64 chars). The raw token is what travels in the
 * email link; the tokenizer stores only the SHA-256 hash so a database/heap dump never leaks
 * the raw token. Entries are single-use: consume marks the entry used and a second consume
 * fails. Expired entries are pruned lazily on consume / issue.
 *
 * <p>Storage is process-local. For multi-node deployments a single replica or sticky session
 * is required; see the README "Notes &amp; limits" section.
 */
public final class MagicLinkTokenizer {

    private static final SecureRandom RNG = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();
    private static final int TOKEN_BYTES = 32;

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong issued = new AtomicLong();
    private final AtomicLong consumed = new AtomicLong();
    private final Clock clock;

    public MagicLinkTokenizer() {
        this(Clock.systemUTC());
    }

    MagicLinkTokenizer(Clock clock) {
        this.clock = clock;
    }

    /**
     * Issues a fresh token bound to the given user / client / redirect URI for the given
     * lifespan. Returns the raw token (only the hash is stored).
     */
    public String issue(String userId, String clientId, String redirectUri, int lifespanSeconds) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId required");
        }
        if (lifespanSeconds <= 0) {
            throw new IllegalArgumentException("lifespanSeconds must be positive");
        }
        pruneExpired();

        byte[] raw = new byte[TOKEN_BYTES];
        RNG.nextBytes(raw);
        String token = HEX.formatHex(raw);
        String hash = hash(token);

        long expiresAt = clock.millis() + (lifespanSeconds * 1000L);
        entries.put(hash, new Entry(userId, clientId, redirectUri, expiresAt));
        issued.incrementAndGet();
        return token;
    }

    /**
     * Consumes the given raw token if it is unexpired and unused. The matching entry is
     * removed on a successful consume so a replay attempt finds nothing.
     */
    public Optional<Entry> consume(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        pruneExpired();

        String hash = hash(token);
        Entry entry = entries.remove(hash);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAt < clock.millis()) {
            return Optional.empty();
        }
        consumed.incrementAndGet();
        return Optional.of(entry);
    }

    public int pending() {
        return entries.size();
    }

    public long totalIssued() {
        return issued.get();
    }

    public long totalConsumed() {
        return consumed.get();
    }

    private void pruneExpired() {
        long now = clock.millis();
        Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expiresAt < now) {
                it.remove();
            }
        }
    }

    private static String hash(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(token.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory on every supported JVM.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Snapshot of token metadata. */
    public static final class Entry {
        private final String userId;
        private final String clientId;
        private final String redirectUri;
        private final long expiresAt;

        Entry(String userId, String clientId, String redirectUri, long expiresAt) {
            this.userId = userId;
            this.clientId = clientId;
            this.redirectUri = redirectUri;
            this.expiresAt = expiresAt;
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

        public long expiresAt() {
            return expiresAt;
        }
    }
}
