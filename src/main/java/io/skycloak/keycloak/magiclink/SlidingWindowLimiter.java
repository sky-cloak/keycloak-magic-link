package io.skycloak.keycloak.magiclink;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure sliding-window rate-limit arithmetic, kept free of any Keycloak types so it can be
 * unit-tested without a running server.
 *
 * <p>State is a list of epoch-second timestamps of prior accepted hits inside the current
 * window. {@link #evaluate} prunes timestamps older than the window, counts what remains,
 * decides whether a fresh hit at {@code nowSeconds} is allowed, and returns the next state to
 * persist. The caller stores {@link Decision#timestamps()} back into the shared store.
 */
final class SlidingWindowLimiter {

    private SlidingWindowLimiter() {
    }

    /**
     * Evaluates a hit at {@code nowSeconds} against the prior {@code timestamps}.
     *
     * @param timestamps prior accepted-hit timestamps (epoch seconds); may be unsorted / contain
     *                   stale entries. Never mutated.
     * @param nowSeconds current time in epoch seconds.
     * @param windowSeconds size of the sliding window; entries strictly older than
     *                      {@code nowSeconds - windowSeconds} are pruned.
     * @param limit maximum number of hits permitted inside the window. A non-positive limit
     *              disables the limiter (always allowed, no state retained).
     * @return the decision plus the timestamp list to persist.
     */
    static Decision evaluate(List<Long> timestamps, long nowSeconds, long windowSeconds, int limit) {
        if (limit <= 0) {
            // Limiter disabled: never block, keep no state.
            return new Decision(true, List.of(), 0L);
        }

        long cutoff = nowSeconds - windowSeconds;
        List<Long> pruned = new ArrayList<>();
        if (timestamps != null) {
            for (Long ts : timestamps) {
                if (ts != null && ts > cutoff) {
                    pruned.add(ts);
                }
            }
        }

        if (pruned.size() >= limit) {
            // Breach: do NOT record this hit (so a throttled caller cannot push the window
            // forward and starve themselves indefinitely). retryAfter is how long until the
            // oldest in-window hit ages out, leaving room for one more.
            long oldest = pruned.get(0);
            for (Long ts : pruned) {
                if (ts < oldest) {
                    oldest = ts;
                }
            }
            long retryAfter = (oldest + windowSeconds) - nowSeconds;
            if (retryAfter < 1) {
                retryAfter = 1;
            }
            return new Decision(false, pruned, retryAfter);
        }

        // Allowed: record this hit.
        pruned.add(nowSeconds);
        return new Decision(true, pruned, 0L);
    }

    /** Outcome of a sliding-window evaluation. */
    static final class Decision {
        private final boolean allowed;
        private final List<Long> timestamps;
        private final long retryAfterSeconds;

        Decision(boolean allowed, List<Long> timestamps, long retryAfterSeconds) {
            this.allowed = allowed;
            this.timestamps = timestamps;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        boolean allowed() {
            return allowed;
        }

        List<Long> timestamps() {
            return timestamps;
        }

        /** Seconds the caller should wait before retrying; only meaningful when blocked. */
        long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }
}
