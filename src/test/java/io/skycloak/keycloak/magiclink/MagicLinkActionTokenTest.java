package io.skycloak.keycloak.magiclink;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MagicLinkActionTokenTest {
    @Test
    void retainsAllContinuationParameters() {
        MagicLinkActionToken token = new MagicLinkActionToken("user", 123, "client", "https://app/cb",
                "openid", "state", "nonce", "challenge", "S256", "query", "device", "consume");

        assertEquals("https://app/cb", token.getRedirectUri());
        assertEquals("openid", token.getScope());
        assertEquals("state", token.getState());
        assertEquals("nonce", token.getNonce());
        assertEquals("challenge", token.getCodeChallenge());
        assertEquals("S256", token.getCodeChallengeMethod());
        assertEquals("query", token.getResponseMode());
        assertEquals("device", token.getDeviceNonce());
        assertEquals("consume", token.getConsumeId());
    }
}
