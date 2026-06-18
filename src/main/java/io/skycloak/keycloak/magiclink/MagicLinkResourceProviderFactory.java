package io.skycloak.keycloak.magiclink;

import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.services.resource.RealmResourceProvider;
import org.keycloak.services.resource.RealmResourceProviderFactory;

/** Mounts the admin API under {@code /realms/{realm}/skycloak-magic-link} (Mode B). */
public final class MagicLinkResourceProviderFactory implements RealmResourceProviderFactory {

    public static final String ID = "skycloak-magic-link";

    private static final Logger LOG = Logger.getLogger(MagicLinkResourceProviderFactory.class);

    @Override
    public RealmResourceProvider create(KeycloakSession session) {
        return new MagicLinkResourceProvider(session);
    }

    @Override
    public void init(Config.Scope scope) {
        LOG.infof("%s admin API initialized at /realms/{realm}/%s", Version.NAME, ID);
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // no-op
    }

    @Override
    public void close() {
        // no-op
    }

    @Override
    public String getId() {
        return ID;
    }
}
