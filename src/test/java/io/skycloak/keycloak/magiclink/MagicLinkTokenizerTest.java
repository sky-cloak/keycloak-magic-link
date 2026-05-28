package io.skycloak.keycloak.magiclink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class MagicLinkTokenizerTest {

    @Test
    void issuedTokenConsumesOnceAndOnlyOnce() {
        MagicLinkTokenizer t = new MagicLinkTokenizer();
        String token = t.issue("user-1", "my-app", "https://app.example/back", 60);

        Optional<MagicLinkTokenizer.Entry> first = t.consume(token);
        assertTrue(first.isPresent(), "first consume must succeed");
        assertEquals("user-1", first.get().userId());
        assertEquals("my-app", first.get().clientId());
        assertEquals("https://app.example/back", first.get().redirectUri());
        assertEquals(1, t.totalConsumed());

        Optional<MagicLinkTokenizer.Entry> second = t.consume(token);
        assertFalse(second.isPresent(), "tokens are single-use - second consume must fail");
    }

    @Test
    void expiredTokenCannotBeConsumed() {
        AtomicLong now = new AtomicLong(1_000_000L);
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneId.systemDefault();
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.ofEpochMilli(now.get());
            }

            @Override
            public long millis() {
                return now.get();
            }
        };
        MagicLinkTokenizer t = new MagicLinkTokenizer(clock);
        String token = t.issue("user-1", "my-app", "https://app.example/back", 1);

        // Advance past the 1-second lifespan.
        now.addAndGet(2_000L);
        Optional<MagicLinkTokenizer.Entry> result = t.consume(token);
        assertFalse(result.isPresent(), "expired tokens must be rejected");
        // Expired entries are pruned out of the pending set.
        assertEquals(0, t.pending(), "expired entries get pruned");
    }

    @Test
    void unknownTokenIsRejected() {
        MagicLinkTokenizer t = new MagicLinkTokenizer();
        assertFalse(t.consume("not-a-real-token").isPresent());
        assertFalse(t.consume(null).isPresent());
        assertFalse(t.consume("").isPresent());
    }

    @Test
    void issuedTokensAreUniquePerCall() {
        MagicLinkTokenizer t = new MagicLinkTokenizer();
        String a = t.issue("user-1", "my-app", "https://app.example/back", 60);
        String b = t.issue("user-1", "my-app", "https://app.example/back", 60);
        assertNotEquals(a, b, "every issue must produce a fresh token");
        assertEquals(2, t.totalIssued());
        assertEquals(2, t.pending());
    }

    @Test
    void invalidIssueArgumentsAreRejected() {
        MagicLinkTokenizer t = new MagicLinkTokenizer();
        assertThrows(IllegalArgumentException.class,
                () -> t.issue(null, "client", "https://x", 60));
        assertThrows(IllegalArgumentException.class,
                () -> t.issue("", "client", "https://x", 60));
        assertThrows(IllegalArgumentException.class,
                () -> t.issue("user", "client", "https://x", 0));
    }
}
