package io.skycloak.keycloak.magiclink;

import org.keycloak.models.KeycloakSession;
import org.keycloak.services.resource.RealmResourceProvider;

public final class MagicLinkResourceProvider implements RealmResourceProvider {

    private final KeycloakSession session;
    private final MagicLinkConfig config;
    private final MagicLinkTokenizer tokenizer;
    private final MagicLinkRateLimiter rateLimiter;

    public MagicLinkResourceProvider(KeycloakSession session,
                                     MagicLinkConfig config,
                                     MagicLinkTokenizer tokenizer,
                                     MagicLinkRateLimiter rateLimiter) {
        this.session = session;
        this.config = config;
        this.tokenizer = tokenizer;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public Object getResource() {
        return new MagicLinkResource(session, config, tokenizer, rateLimiter);
    }

    @Override
    public void close() {
        // no-op
    }
}
