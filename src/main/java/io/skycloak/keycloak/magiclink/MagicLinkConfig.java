package io.skycloak.keycloak.magiclink;

import org.keycloak.Config;

/**
 * Static configuration read once at server start from the realm-resource SPI scope.
 *
 * <pre>
 *   --spi-realm-restapi-provider-magic-link-token-lifespan-seconds=600
 *   --spi-realm-restapi-provider-magic-link-from-email-override=noreply@example.test
 *   --spi-realm-restapi-provider-magic-link-subject-template=Your sign-in link
 *   --spi-realm-restapi-provider-magic-link-body-template=Click to sign in: {link}
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

    private final int tokenLifespanSeconds;
    private final String fromEmailOverride;
    private final String subjectTemplate;
    private final String bodyTemplate;

    MagicLinkConfig(int tokenLifespanSeconds, String fromEmailOverride,
                    String subjectTemplate, String bodyTemplate) {
        this.tokenLifespanSeconds = tokenLifespanSeconds;
        this.fromEmailOverride = fromEmailOverride;
        this.subjectTemplate = subjectTemplate;
        this.bodyTemplate = bodyTemplate;
    }

    static MagicLinkConfig from(Config.Scope scope) {
        if (scope == null) {
            return new MagicLinkConfig(600, null, DEFAULT_SUBJECT, DEFAULT_BODY);
        }
        int lifespan = scope.getInt("token-lifespan-seconds", 600);
        String fromOverride = trimOrNull(scope.get("from-email-override"));
        String subject = orDefault(scope.get("subject-template"), DEFAULT_SUBJECT);
        String body = orDefault(scope.get("body-template"), DEFAULT_BODY);
        return new MagicLinkConfig(lifespan, fromOverride, subject, body);
    }

    public int tokenLifespanSeconds() {
        return tokenLifespanSeconds;
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
