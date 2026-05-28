package io.skycloak.keycloak.magiclink;

import org.keycloak.Config;

/**
 * Static configuration read once at server start from the realm-resource SPI scope. The SPI is
 * named {@code realm-restapi-extension} and this provider's id is {@code magic-link}, so the flag
 * prefix is {@code --spi-realm-restapi-extension-magic-link-...}.
 *
 * <pre>
 *   --spi-realm-restapi-extension-magic-link-token-lifespan-seconds=600
 *   --spi-realm-restapi-extension-magic-link-from-email-override=noreply@example.test
 *   --spi-realm-restapi-extension-magic-link-subject-template=Your sign-in link
 *   --spi-realm-restapi-extension-magic-link-body-template=Click to sign in: {link}
 *   --spi-realm-restapi-extension-magic-link-requests-per-minute-per-ip=5
 *   --spi-realm-restapi-extension-magic-link-requests-per-minute-per-email=3
 * </pre>
 *
 * The {@code body-template} value may contain the placeholder {@code {link}}; it will be
 * substituted with the consume URL. If the placeholder is absent the link is appended.
 */
public final class MagicLinkConfig {

    private static final String DEFAULT_SUBJECT = "Your sign-in link";
    private static final String DEFAULT_BODY =
            "Hi,\n\nClick the link below to sign in. It expires shortly and can be used once.\n\n"
                    + "{link}\n\nIf you did not request this email you can safely ignore it.\n";

    private static final int DEFAULT_PER_IP = 5;
    private static final int DEFAULT_PER_EMAIL = 3;

    private final int tokenLifespanSeconds;
    private final String fromEmailOverride;
    private final String subjectTemplate;
    private final String bodyTemplate;
    private final int requestsPerMinutePerIp;
    private final int requestsPerMinutePerEmail;

    MagicLinkConfig(int tokenLifespanSeconds, String fromEmailOverride,
                    String subjectTemplate, String bodyTemplate,
                    int requestsPerMinutePerIp, int requestsPerMinutePerEmail) {
        this.tokenLifespanSeconds = tokenLifespanSeconds;
        this.fromEmailOverride = fromEmailOverride;
        this.subjectTemplate = subjectTemplate;
        this.bodyTemplate = bodyTemplate;
        this.requestsPerMinutePerIp = requestsPerMinutePerIp;
        this.requestsPerMinutePerEmail = requestsPerMinutePerEmail;
    }

    static MagicLinkConfig from(Config.Scope scope) {
        if (scope == null) {
            return new MagicLinkConfig(600, null, DEFAULT_SUBJECT, DEFAULT_BODY,
                    DEFAULT_PER_IP, DEFAULT_PER_EMAIL);
        }
        int lifespan = scope.getInt("token-lifespan-seconds", 600);
        String fromOverride = trimOrNull(scope.get("from-email-override"));
        String subject = orDefault(scope.get("subject-template"), DEFAULT_SUBJECT);
        String body = orDefault(scope.get("body-template"), DEFAULT_BODY);
        int perIp = scope.getInt("requests-per-minute-per-ip", DEFAULT_PER_IP);
        int perEmail = scope.getInt("requests-per-minute-per-email", DEFAULT_PER_EMAIL);
        return new MagicLinkConfig(lifespan, fromOverride, subject, body, perIp, perEmail);
    }

    public int tokenLifespanSeconds() {
        return tokenLifespanSeconds;
    }

    /** Max {@code POST /request} calls per minute from a single client IP. 0 disables. */
    public int requestsPerMinutePerIp() {
        return requestsPerMinutePerIp;
    }

    /** Max {@code POST /request} calls per minute for a single submitted email. 0 disables. */
    public int requestsPerMinutePerEmail() {
        return requestsPerMinutePerEmail;
    }

    public String fromEmailOverride() {
        return fromEmailOverride;
    }

    public String subjectTemplate() {
        return subjectTemplate;
    }

    public String bodyTemplate() {
        return bodyTemplate;
    }

    public String renderBody(String link) {
        String template = bodyTemplate != null ? bodyTemplate : DEFAULT_BODY;
        if (template.contains("{link}")) {
            return template.replace("{link}", link);
        }
        return template + (template.endsWith("\n") ? "" : "\n") + link + "\n";
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String trimOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
