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

    @Override
    public RealmResourceProvider create(KeycloakSession session) {
        // The tokenizer and rate limiter are per-session: they wrap the session-scoped
        // SingleUseObjectProvider (Infinispan), so token state and rate-limit windows are
        // shared cluster-wide rather than held in this factory's heap.
        MagicLinkTokenizer tokenizer = new MagicLinkTokenizer(session);
        MagicLinkRateLimiter rateLimiter = new MagicLinkRateLimiter(session);
        return new MagicLinkResourceProvider(session, config, tokenizer, rateLimiter);
    }

    @Override
    public void init(Config.Scope scope) {
        this.config = MagicLinkConfig.from(scope);
        LOG.infof("%s initialized (token-lifespan=%ds, from-override=%s, rl-per-ip=%d/min, rl-per-email=%d/min)",
                Version.NAME,
                config.tokenLifespanSeconds(),
                config.fromEmailOverride() != null ? config.fromEmailOverride() : "<realm default>",
                config.requestsPerMinutePerIp(),
                config.requestsPerMinutePerEmail());
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
