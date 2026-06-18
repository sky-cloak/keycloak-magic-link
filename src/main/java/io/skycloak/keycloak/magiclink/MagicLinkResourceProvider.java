package io.skycloak.keycloak.magiclink;

import org.keycloak.models.KeycloakSession;
import org.keycloak.services.resource.RealmResourceProvider;

public final class MagicLinkResourceProvider implements RealmResourceProvider {

    private final KeycloakSession session;

    public MagicLinkResourceProvider(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public Object getResource() {
        return new MagicLinkResource(session);
    }

    @Override
    public void close() {
        // no-op
    }
}
