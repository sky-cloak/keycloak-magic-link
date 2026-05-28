package io.skycloak.keycloak.magiclink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pure-logic tests for token key derivation. The full SingleUseObjectProvider store path
 * (issue / consume / single-use / expiry) needs a running Keycloak and is covered by the
 * integration test.
 */
class MagicLinkTokenizerTest {

    @Test
    void keyDerivationIsPrefixedSha256Hex() {
        String token = "0123456789abcdef";
        String key = MagicLinkTokenizer.keyFor(token);

        assertTrue(key.startsWith(MagicLinkTokenizer.KEY_PREFIX),
                "key must carry the namespace prefix");
        String hex = key.substring(MagicLinkTokenizer.KEY_PREFIX.length());
        assertEquals(64, hex.length(), "SHA-256 hex is 64 chars");
        assertTrue(hex.matches("[0-9a-f]{64}"), "hex must be lower-case 0-9a-f");
    }

    @Test
    void keyDerivationIsDeterministicAndUnique() {
        assertEquals(MagicLinkTokenizer.keyFor("token-a"), MagicLinkTokenizer.keyFor("token-a"),
                "same token must derive the same key");
        assertNotEquals(MagicLinkTokenizer.keyFor("token-a"), MagicLinkTokenizer.keyFor("token-b"),
                "different tokens must derive different keys");
    }

    @Test
    void sha256hexMatchesKnownVector() {
        // SHA-256("") = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                Hashing.sha256hex(""));
    }
}
