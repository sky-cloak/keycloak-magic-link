package io.skycloak.keycloak.magiclink;

import org.keycloak.Config;
import org.keycloak.authentication.actiontoken.ActionTokenHandlerFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

/**
 * Routes {@code skycloak-magic-link} action tokens to {@link MagicLinkActionTokenHandler}. The id
 * must equal the token type so the action-token framework dispatches correctly.
 */
public final class MagicLinkActionTokenHandlerFactory
        implements ActionTokenHandlerFactory<MagicLinkActionToken> {

    @Override
    public MagicLinkActionTokenHandler create(KeycloakSession session) {
        return new MagicLinkActionTokenHandler();
    }

    @Override
    public String getId() {
        return MagicLinkActionToken.TOKEN_TYPE;
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
