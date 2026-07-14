package io.skycloak.keycloak.magiclink;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.SingleUseObjectProvider;

class MagicLinkRateLimiterTest {
    private final KeycloakSession session = mock(KeycloakSession.class);
    private final SingleUseObjectProvider store = mock(SingleUseObjectProvider.class);
    private final MagicLinkRateLimiter limiter = new MagicLinkRateLimiter(session,
            Clock.fixed(Instant.ofEpochSecond(100), ZoneOffset.UTC));

    MagicLinkRateLimiterTest() {
        when(session.singleUseObjects()).thenReturn(store);
    }

    @Test
    void disabledLimitsDoNotTouchTheStore() {
        MagicLinkRateLimiter.Decision result = limiter.check(null, "alice@example.test", 0, -1);
        assertTrue(result.allowed());
        verify(store, org.mockito.Mockito.never()).get(any());
    }

    @Test
    void persistsAcceptedWindowsUsingFallbackIpAndEmailHash() {
        when(store.get(any())).thenReturn(null);

        MagicLinkRateLimiter.Decision result = limiter.check(" ", "alice@example.test", 2, 2);

        assertTrue(result.allowed());
        verify(store).put(eq(MagicLinkRateLimiter.IP_KEY_PREFIX + "unknown"), eq(60L),
                eq(Map.of("ts", "100")));
        verify(store).put(eq(MagicLinkRateLimiter.EMAIL_KEY_PREFIX + Hashing.sha256hex("alice@example.test")),
                eq(60L), eq(Map.of("ts", "100")));
    }

    @Test
    void rejectsFullWindowWithoutExtendingItAndHandlesCorruptEntries() {
        when(store.get(any())).thenReturn(null);
        when(store.get(MagicLinkRateLimiter.IP_KEY_PREFIX + "127.0.0.1"))
                .thenReturn(Map.of("ts", "50, garbage, 60"));

        MagicLinkRateLimiter.Decision result = limiter.check("127.0.0.1", "alice@example.test", 2, 0);

        assertFalse(result.allowed());
        verify(store, org.mockito.Mockito.never()).put(
                eq(MagicLinkRateLimiter.IP_KEY_PREFIX + "127.0.0.1"), anyLong(), any());
    }
}
