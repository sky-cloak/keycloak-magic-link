package io.skycloak.keycloak.magiclink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.keycloak.models.AuthenticatorConfigModel;

class MagicLinkAuthConfigTest {
    @Test
    void usesDefaultsForMissingOrInvalidConfiguration() {
        AuthenticatorConfigModel model = mock(AuthenticatorConfigModel.class);
        when(model.getConfig()).thenReturn(Map.of(
                MagicLinkAuthConfig.TOKEN_LIFESPAN_SECONDS, "not-a-number",
                MagicLinkAuthConfig.AUTO_CREATE_USER, " "));

        MagicLinkAuthConfig config = new MagicLinkAuthConfig(model);

        assertEquals(600, config.tokenLifespanSeconds());
        assertFalse(config.autoCreateUser());
        assertEquals(5, config.requestsPerMinutePerIp());
        assertEquals(3, config.requestsPerMinutePerEmail());
    }

    @Test
    void readsAndTrimsConfiguredValues() {
        AuthenticatorConfigModel model = mock(AuthenticatorConfigModel.class);
        when(model.getConfig()).thenReturn(Map.of(
                MagicLinkAuthConfig.TOKEN_LIFESPAN_SECONDS, " 120 ",
                MagicLinkAuthConfig.AUTO_CREATE_USER, " TrUe ",
                MagicLinkAuthConfig.PER_IP, "0",
                MagicLinkAuthConfig.PER_EMAIL, "9"));

        MagicLinkAuthConfig config = new MagicLinkAuthConfig(model);

        assertEquals(120, config.tokenLifespanSeconds());
        assertTrue(config.autoCreateUser());
        assertEquals(0, config.requestsPerMinutePerIp());
        assertEquals(9, config.requestsPerMinutePerEmail());
    }

    @Test
    void acceptsNoModel() {
        MagicLinkAuthConfig config = new MagicLinkAuthConfig(null);
        assertEquals(600, config.tokenLifespanSeconds());
    }
}
