package io.skycloak.keycloak.magiclink;

import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.services.resource.RealmResourceProvider;
import org.keycloak.services.resource.RealmResourceProviderFactory;

/** Mounts the magic-link endpoints under {@code /realms/{realm}/magic-link}. */
public final class MagicLinkResourceProviderFactory implements RealmResourceProviderFactory {

    public static final String ID = "magic-link";

    private static final Logger LOG = Logger.getLogger(MagicLinkResourceProviderFactory.class);

    private MagicLinkConfig config;
    private MagicLinkTokenizer tokenizer;

    @Override
    public RealmResourceProvider create(KeycloakSession session) {
        return new MagicLinkResourceProvider(session, config, tokenizer);
    }

    @Override
    public void init(Config.Scope scope) {
        this.config = MagicLinkConfig.from(scope);
        this.tokenizer = new MagicLinkTokenizer();
        LOG.infof("%s initialized (token-lifespan=%ds, from-override=%s)",
                Version.NAME,
                config.tokenLifespanSeconds(),
                config.fromEmailOverride() != null ? config.fromEmailOverride() : "<realm default>");
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
