package io.skycloak.keycloak.magiclink;

import java.util.List;

import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel.Requirement;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;

/**
 * Registers the standalone magic-link authenticator and its per-flow configuration. Provider id is
 * the namespaced {@code skycloak-magic-link} (ADR Q14) to avoid colliding with other magic-link
 * extensions on the same server.
 */
public final class MagicLinkAuthenticatorFactory implements AuthenticatorFactory {

    public static final String PROVIDER_ID = "skycloak-magic-link";

    private static final Requirement[] REQUIREMENT_CHOICES = {
            Requirement.REQUIRED,
            Requirement.ALTERNATIVE,
            Requirement.DISABLED,
    };

    @Override
    public Authenticator create(KeycloakSession session) {
        return new MagicLinkAuthenticator();
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "Magic Link (Skycloak)";
    }

    @Override
    public String getReferenceCategory() {
        return "magic-link";
    }

    @Override
    public boolean isConfigurable() {
        return true;
    }

    @Override
    public Requirement[] getRequirementChoices() {
        return REQUIREMENT_CHOICES;
    }

    @Override
    public boolean isUserSetupAllowed() {
        return false;
    }

    @Override
    public String getHelpText() {
        return "Sends a passwordless, single-use sign-in link to the user's email and completes the "
                + "login when the link is opened in the same browser.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return ProviderConfigurationBuilder.create()
                .property()
                    .name(MagicLinkAuthConfig.TOKEN_LIFESPAN_SECONDS)
                    .label("Link lifespan (seconds)")
                    .type(ProviderConfigProperty.STRING_TYPE)
                    .defaultValue(String.valueOf(MagicLinkAuthConfig.DEFAULT_LIFESPAN))
                    .helpText("How long a magic link remains valid, in seconds.")
                    .add()
                .property()
                    .name(MagicLinkAuthConfig.SAME_DEVICE)
                    .label("Require same device")
                    .type(ProviderConfigProperty.BOOLEAN_TYPE)
                    .defaultValue(String.valueOf(MagicLinkAuthConfig.DEFAULT_SAME_DEVICE))
                    .helpText("Only complete the sign-in in the same browser that requested the "
                            + "link. Strongly recommended; turning it off allows cross-device use.")
                    .add()
                .property()
                    .name(MagicLinkAuthConfig.AUTO_CREATE_USER)
                    .label("Create user if none exists")
                    .type(ProviderConfigProperty.BOOLEAN_TYPE)
                    .defaultValue(String.valueOf(MagicLinkAuthConfig.DEFAULT_AUTO_CREATE))
                    .helpText("When enabled, an unknown email creates a new user. Off by default so "
                            + "the login form cannot be used to mint accounts.")
                    .add()
                .property()
                    .name(MagicLinkAuthConfig.PER_IP)
                    .label("Requests per minute per IP")
                    .type(ProviderConfigProperty.STRING_TYPE)
                    .defaultValue(String.valueOf(MagicLinkAuthConfig.DEFAULT_PER_IP))
                    .helpText("Max link requests per minute from one client IP. 0 disables.")
                    .add()
                .property()
                    .name(MagicLinkAuthConfig.PER_EMAIL)
                    .label("Requests per minute per email")
                    .type(ProviderConfigProperty.STRING_TYPE)
                    .defaultValue(String.valueOf(MagicLinkAuthConfig.DEFAULT_PER_EMAIL))
                    .helpText("Max link requests per minute for one email address. 0 disables.")
                    .add()
                .build();
    }

    @Override
    public void init(Config.Scope config) {
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
    }

    @Override
    public void close() {
    }
}
