package io.skycloak.keycloak.magiclink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class SlidingWindowLimiterTest {

    @Test
    void calculatesTheOldestExpiryAndClampsRetryAfter() {
        SlidingWindowLimiter.Decision decision = SlidingWindowLimiter.evaluate(
                java.util.List.of(99L, 91L), 100L, 10L, 2);

        assertFalse(decision.allowed());
        assertEquals(1L, decision.retryAfterSeconds());
    }

    private static final long WINDOW = 60L;

    @Test
    void underLimitIsAllowedAndRecordsHit() {
        SlidingWindowLimiter.Decision d =
                SlidingWindowLimiter.evaluate(List.of(100L, 101L), 110L, WINDOW, 5);
        assertTrue(d.allowed(), "two prior hits under a limit of 5 must be allowed");
        assertEquals(List.of(100L, 101L, 110L), d.timestamps(), "the new hit is appended");
        assertEquals(0L, d.retryAfterSeconds());
    }

    @Test
    void atLimitIsBlockedAndDoesNotRecordHit() {
        // Three hits in-window with a limit of 3 -> the fourth is blocked.
        List<Long> prior = List.of(100L, 110L, 120L);
        SlidingWindowLimiter.Decision d = SlidingWindowLimiter.evaluate(prior, 130L, WINDOW, 3);
        assertFalse(d.allowed(), "a hit at the limit must be blocked");
        // The blocked hit must NOT be recorded, or a throttled caller would starve themselves.
        assertEquals(prior, d.timestamps(), "blocked hit is not appended");
    }

    @Test
    void retryAfterIsTimeUntilOldestInWindowAgesOut() {
        // Oldest is 100; window 60 -> it ages out at t=160. now=130 -> retryAfter 30.
        SlidingWindowLimiter.Decision d =
                SlidingWindowLimiter.evaluate(List.of(100L, 110L, 120L), 130L, WINDOW, 3);
        assertFalse(d.allowed());
        assertEquals(30L, d.retryAfterSeconds());
    }

    @Test
    void oldestEntryPrunedAtWindowBoundaryFreesASlot() {
        // now=160, cutoff = 160-60 = 100. Entry at exactly 100 is NOT > cutoff so it is pruned,
        // leaving [110,120] = 2 < limit 3 -> allowed (the boundary entry has aged out).
        SlidingWindowLimiter.Decision d =
                SlidingWindowLimiter.evaluate(List.of(100L, 110L, 120L), 160L, WINDOW, 3);
        assertTrue(d.allowed(), "the entry exactly at the window boundary is pruned, freeing a slot");
    }

    @Test
    void retryAfterIsNeverBelowOneWhenBlocked() {
        // The newest possible in-window entries: all at now. Oldest = now, so it ages out at
        // now+window; retryAfter would be exactly the window. Verify it is always >= 1.
        SlidingWindowLimiter.Decision d =
                SlidingWindowLimiter.evaluate(List.of(130L, 130L, 130L), 130L, WINDOW, 3);
        assertFalse(d.allowed());
        assertTrue(d.retryAfterSeconds() >= 1, "Retry-After must never be zero or negative");
    }

    @Test
    void staleTimestampsArePrunedBeforeCounting() {
        // limit 3, but two of the five priors are older than the 60s window from now=200.
        // cutoff = 140; entries 100 and 130 are pruned, leaving 150,160,170 -> 3 in-window.
        List<Long> prior = List.of(100L, 130L, 150L, 160L, 170L);
        SlidingWindowLimiter.Decision d = SlidingWindowLimiter.evaluate(prior, 200L, WINDOW, 3);
        assertFalse(d.allowed(), "three fresh hits at limit 3 block the fourth");
        assertEquals(List.of(150L, 160L, 170L), d.timestamps(), "stale entries are dropped from state");
    }

    @Test
    void prunedListLetsPreviouslyBlockedCallerThroughLater() {
        // Window fills up...
        List<Long> prior = new ArrayList<>(List.of(10L, 20L, 30L));
        SlidingWindowLimiter.Decision blocked = SlidingWindowLimiter.evaluate(prior, 35L, WINDOW, 3);
        assertFalse(blocked.allowed());

        // ...then enough time passes that the oldest ages out: now=75, cutoff=15 drops 10.
        SlidingWindowLimiter.Decision later =
                SlidingWindowLimiter.evaluate(blocked.timestamps(), 75L, WINDOW, 3);
        assertTrue(later.allowed(), "once the oldest hit ages out, a new hit is allowed");
        assertEquals(List.of(20L, 30L, 75L), later.timestamps());
    }

    @Test
    void zeroOrNegativeLimitDisablesTheLimiter() {
        SlidingWindowLimiter.Decision zero =
                SlidingWindowLimiter.evaluate(List.of(1L, 2L, 3L, 4L, 5L), 6L, WINDOW, 0);
        assertTrue(zero.allowed(), "limit 0 disables the limiter");
        assertTrue(zero.timestamps().isEmpty(), "a disabled limiter retains no state");

        SlidingWindowLimiter.Decision negative =
                SlidingWindowLimiter.evaluate(List.of(1L, 2L), 3L, WINDOW, -1);
        assertTrue(negative.allowed());
    }

    @Test
    void nullAndEmptyPriorStateAreTreatedAsNoHits() {
        SlidingWindowLimiter.Decision fromNull = SlidingWindowLimiter.evaluate(null, 10L, WINDOW, 3);
        assertTrue(fromNull.allowed());
        assertEquals(List.of(10L), fromNull.timestamps());

        SlidingWindowLimiter.Decision fromEmpty = SlidingWindowLimiter.evaluate(List.of(), 10L, WINDOW, 3);
        assertTrue(fromEmpty.allowed());
        assertEquals(List.of(10L), fromEmpty.timestamps());
    }
}
