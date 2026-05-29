package io.skycloak.keycloak.magiclink;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;

import org.junit.jupiter.api.Test;

/**
 * Pure-logic tests for {@link MagicLinkResource} helpers that do not require a running Keycloak.
 * The full request/consume flow is covered by the integration test.
 */
class MagicLinkResourceTest {

    @Test
    void emailNormalizationCollapsesCasingAndWhitespace() {
        // Keycloak resolves users by email case-insensitively, so every casing/whitespace variant
        // of one address must collapse to a single rate-limit key, otherwise an attacker mints a
        // fresh per-email window per variant and amplifies mail past the configured limit.
        String canonical = "user@example.com";
        assertEquals(canonical, MagicLinkResource.normalizeEmail("user@example.com"));
        assertEquals(canonical, MagicLinkResource.normalizeEmail("User@Example.com"));
        assertEquals(canonical, MagicLinkResource.normalizeEmail("USER@EXAMPLE.COM"));
        assertEquals(canonical, MagicLinkResource.normalizeEmail("  user@example.com  "));
        assertEquals(canonical, MagicLinkResource.normalizeEmail("\tUSER@Example.COM\n"));
    }

    @Test
    void normalizedVariantsDeriveTheSameRateLimitKey() {
        // The per-email window keys on sha256(normalized email); all casings must hash equal.
        String base = Hashing.sha256hex(MagicLinkResource.normalizeEmail("victim@example.com"));
        assertEquals(base, Hashing.sha256hex(MagicLinkResource.normalizeEmail("Victim@Example.Com")));
        assertEquals(base, Hashing.sha256hex(MagicLinkResource.normalizeEmail("VICTIM@EXAMPLE.COM")));
        assertEquals(base, Hashing.sha256hex(MagicLinkResource.normalizeEmail(" victim@example.com ")));
    }

    @Test
    void normalizationUsesRootLocaleNotDefault() {
        // Guard against locale-specific casing (e.g. Turkish dotless-i) producing a different key
        // than Keycloak's lookup, which would let a variant slip the window on certain JVM locales.
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            assertEquals("filip@example.com", MagicLinkResource.normalizeEmail("FILIP@EXAMPLE.COM"));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void nullEmailNormalizesToEmptyString() {
        assertEquals("", MagicLinkResource.normalizeEmail(null));
    }
}
