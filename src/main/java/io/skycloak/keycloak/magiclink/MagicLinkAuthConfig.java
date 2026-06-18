package io.skycloak.keycloak.magiclink;

import org.keycloak.models.AuthenticatorConfigModel;

/**
 * Typed accessor over the per-flow {@link AuthenticatorConfigModel} for the magic-link
 * authenticator. Per ADR (Q9) configuration is per-flow and admin-editable, not server-global, so
 * each realm/flow can tune lifespan, security posture, and rate limits independently.
 */
final class MagicLinkAuthConfig {

    static final String TOKEN_LIFESPAN_SECONDS = "token-lifespan-seconds";
    static final String SAME_DEVICE = "same-device";
    static final String AUTO_CREATE_USER = "auto-create-user";
    static final String PER_IP = "requests-per-minute-per-ip";
    static final String PER_EMAIL = "requests-per-minute-per-email";

    static final int DEFAULT_LIFESPAN = 600;
    static final boolean DEFAULT_SAME_DEVICE = true;
    static final boolean DEFAULT_AUTO_CREATE = false;
    static final int DEFAULT_PER_IP = 5;
    static final int DEFAULT_PER_EMAIL = 3;

    private final AuthenticatorConfigModel model;

    MagicLinkAuthConfig(AuthenticatorConfigModel model) {
        this.model = model;
    }

    int tokenLifespanSeconds() {
        return getInt(TOKEN_LIFESPAN_SECONDS, DEFAULT_LIFESPAN);
    }

    boolean sameDevice() {
        return getBool(SAME_DEVICE, DEFAULT_SAME_DEVICE);
    }

    boolean autoCreateUser() {
        return getBool(AUTO_CREATE_USER, DEFAULT_AUTO_CREATE);
    }

    int requestsPerMinutePerIp() {
        return getInt(PER_IP, DEFAULT_PER_IP);
    }

    int requestsPerMinutePerEmail() {
        return getInt(PER_EMAIL, DEFAULT_PER_EMAIL);
    }

    private String get(String key) {
        if (model == null || model.getConfig() == null) {
            return null;
        }
        return model.getConfig().get(key);
    }

    private int getInt(String key, int fallback) {
        String v = get(key);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private boolean getBool(String key, boolean fallback) {
        String v = get(key);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(v.trim());
    }
}
