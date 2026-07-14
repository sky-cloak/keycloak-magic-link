package io.skycloak.keycloak.magiclink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.keycloak.models.KeycloakSession;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.http.HttpRequest;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;

class ProviderFactoryTest {
    @Test
    void authenticatorFactoryDescribesAndCreatesTheProvider() {
        MagicLinkAuthenticatorFactory factory = new MagicLinkAuthenticatorFactory();

        assertEquals(MagicLinkAuthenticatorFactory.PROVIDER_ID, factory.getId());
        assertEquals("Magic Link (Skycloak)", factory.getDisplayType());
        assertEquals("magic-link", factory.getReferenceCategory());
        assertTrue(factory.isConfigurable());
        assertFalse(factory.isUserSetupAllowed());
        assertTrue(factory.getHelpText().contains("passwordless"));
        assertInstanceOf(MagicLinkAuthenticator.class, factory.create(mock(KeycloakSession.class)));
        assertEquals(4, factory.getConfigProperties().size());
        assertEquals(MagicLinkAuthConfig.TOKEN_LIFESPAN_SECONDS, factory.getConfigProperties().get(0).getName());
        assertEquals(ProviderConfigProperty.STRING_TYPE, factory.getConfigProperties().get(0).getType());
    }

    @Test
    void resourceAndActionTokenFactoriesExposeTheirIdsAndProviders() {
        KeycloakSession session = mock(KeycloakSession.class);
        MagicLinkResourceProviderFactory resourceFactory = new MagicLinkResourceProviderFactory();
        MagicLinkActionTokenHandlerFactory tokenFactory = new MagicLinkActionTokenHandlerFactory();

        assertEquals(MagicLinkResourceProviderFactory.ID, resourceFactory.getId());
        assertInstanceOf(MagicLinkResourceProvider.class, resourceFactory.create(session));
        assertEquals(MagicLinkActionToken.TOKEN_TYPE, tokenFactory.getId());
        assertInstanceOf(MagicLinkActionTokenHandler.class, tokenFactory.create(session));
    }

    @Test
    void providersExposeExpectedTrivialContract() {
        MagicLinkAuthenticator authenticator = new MagicLinkAuthenticator();
        assertFalse(authenticator.requiresUser());
        assertTrue(authenticator.configuredFor(null, null, null));
    }

    @Test
    void authenticatorShowsTheMissingEmailFormWithoutTouchingTheSession() {
        AuthenticationFlowContext context = mock(AuthenticationFlowContext.class);
        HttpRequest request = mock(HttpRequest.class);
        LoginFormsProvider form = mock(LoginFormsProvider.class);
        Response response = Response.ok().build();
        when(context.getHttpRequest()).thenReturn(request);
        when(request.getDecodedFormParameters()).thenReturn(new MultivaluedHashMap<>());
        when(context.form()).thenReturn(form);
        when(form.setError("magicLinkMissingEmail")).thenReturn(form);
        when(form.createForm("skycloak-magic-link-form.ftl")).thenReturn(response);

        new MagicLinkAuthenticator().action(context);

        org.mockito.Mockito.verify(context).challenge(response);
    }
}
