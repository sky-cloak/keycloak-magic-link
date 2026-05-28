package io.skycloak.keycloak.magiclink;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.keycloak.models.KeycloakSession;
import org.keycloak.models.SingleUseObjectProvider;

/**
 * Cluster-wide sliding-window rate limiter for {@code POST /request}, backed by Keycloak's
 * {@link SingleUseObjectProvider} (Infinispan). Because the store is shared across the cluster,
 * the limit is enforced cluster-wide rather than per replica.
 *
 * <p>Two independent windows are checked per request: one keyed by client IP and one keyed by
 * the SHA-256 of the submitted email. The email window is keyed by the <em>submitted</em> email
 * regardless of whether it maps to a user, so a 429 looks identical whether or not the account
 * exists (no enumeration via rate-limit timing or shape).
 *
 * <p>The window is 60 seconds, so the single-node cold-restart caveat that applies to the token
 * store is irrelevant here: any cleared window simply resets within a minute.
 */
final class MagicLinkRateLimiter {

    static final String IP_KEY_PREFIX = "skycloak-ml-rl-ip:";
    static final String EMAIL_KEY_PREFIX = "skycloak-ml-rl-email:";
    static final String NOTE_TIMESTAMPS = "ts";

    /** The window is fixed at one minute; the configured limits are "per minute". */
    static final long WINDOW_SECONDS = 60L;

    private final KeycloakSession session;
    private final Clock clock;

    MagicLinkRateLimiter(KeycloakSession session) {
        this(session, Clock.systemUTC());
    }

    MagicLinkRateLimiter(KeycloakSession session, Clock clock) {
        this.session = session;
        this.clock = clock;
    }

    /**
     * Checks both the per-IP and per-email windows for this request and records the hit against
     * any window that still has headroom.
     *
     * @return a {@link Decision}; {@link Decision#allowed()} is {@code false} if either window is
     *         exceeded, in which case {@link Decision#retryAfterSeconds()} is the larger of the
     *         two windows' retry hints.
     */
    Decision check(String clientIp, String email, int perIpLimit, int perEmailLimit) {
        long now = clock.millis() / 1000L;

        Decision ip = evaluateWindow(IP_KEY_PREFIX + safe(clientIp), now, perIpLimit);
        Decision em = evaluateWindow(EMAIL_KEY_PREFIX + Hashing.sha256hex(email), now, perEmailLimit);

        boolean allowed = ip.allowed && em.allowed;
        long retryAfter = Math.max(ip.retryAfterSeconds, em.retryAfterSeconds);
        return new Decision(allowed, retryAfter);
    }

    /**
     * Evaluates one window: read the stored timestamps, prune+count+decide via the pure helper,
     * then persist the next state (only when the limiter is enabled and the hit was accepted, so
     * a breach does not extend the offender's window).
     */
    private Decision evaluateWindow(String key, long now, int limit) {
        if (limit <= 0) {
            return new Decision(true, 0L);
        }
        SingleUseObjectProvider store = session.singleUseObjects();

        List<Long> existing = parse(store.get(key));
        SlidingWindowLimiter.Decision d =
                SlidingWindowLimiter.evaluate(existing, now, WINDOW_SECONDS, limit);

        if (d.allowed()) {
            // Persist the appended timestamp. put() on the Infinispan store is an upsert and
            // refreshes the key's lifespan to a fresh window, keeping the sliding window alive.
            Map<String, String> notes = Map.of(NOTE_TIMESTAMPS, serialize(d.timestamps()));
            // Remove first so the lifespan is reset cleanly; remove is a no-op if absent.
            store.remove(key);
            store.put(key, WINDOW_SECONDS, notes);
        }
        return new Decision(d.allowed(), d.retryAfterSeconds());
    }

    private static List<Long> parse(Map<String, String> notes) {
        if (notes == null) {
            return List.of();
        }
        String raw = notes.get(NOTE_TIMESTAMPS);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<Long> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                out.add(Long.parseLong(trimmed));
            } catch (NumberFormatException ignored) {
                // Skip a corrupt entry rather than failing the whole request.
            }
        }
        return out;
    }

    private static String serialize(List<Long> timestamps) {
        StringBuilder sb = new StringBuilder();
        for (Long ts : timestamps) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(ts);
        }
        return sb.toString();
    }

    private static String safe(String ip) {
        return ip == null || ip.isBlank() ? "unknown" : ip;
    }

    /** Combined outcome of the IP + email windows. */
    static final class Decision {
        private final boolean allowed;
        private final long retryAfterSeconds;

        Decision(boolean allowed, long retryAfterSeconds) {
            this.allowed = allowed;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        boolean allowed() {
            return allowed;
        }

        long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }
}
