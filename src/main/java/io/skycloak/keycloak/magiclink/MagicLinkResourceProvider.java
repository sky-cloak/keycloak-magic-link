package io.skycloak.keycloak.magiclink;

import org.keycloak.models.KeycloakSession;
import org.keycloak.services.resource.RealmResourceProvider;

public final class MagicLinkResourceProvider implements RealmResourceProvider {

    private final KeycloakSession session;
    private final MagicLinkConfig config;
    private final MagicLinkTokenizer tokenizer;

    public MagicLinkResourceProvider(KeycloakSession session,
                                     MagicLinkConfig config,
                                     MagicLinkTokenizer tokenizer) {
        this.session = session;
        this.config = config;
        this.tokenizer = tokenizer;
    }

    @Override
    public Object getResource() {
        return new MagicLinkResource(session, config, tokenizer);
    }

    @Override
    public void close() {
        // no-op
    }
}
