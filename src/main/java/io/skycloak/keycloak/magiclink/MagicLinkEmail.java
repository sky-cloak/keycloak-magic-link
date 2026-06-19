package io.skycloak.keycloak.magiclink;

import java.util.HashMap;
import java.util.Map;

import org.jboss.logging.Logger;
import org.keycloak.email.EmailException;
import org.keycloak.email.EmailSenderProvider;
import org.keycloak.email.EmailTemplateProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

/**
 * Single source for the magic-link email, shared by Mode A (authenticator) and Mode B (admin API).
 * Prefers the themeable FreeMarker template {@code skycloak-magic-link-email.ftl} via
 * {@link EmailTemplateProvider} so realms can override copy and branding through their own theme. If
 * the themed render is unavailable for any reason it falls back to a built-in body, so delivery never
 * depends on theme resolution. The link is the credential: it is passed only to the mail body and is
 * never logged.
 */
final class MagicLinkEmail {

    private static final Logger LOG = Logger.getLogger(MagicLinkEmail.class);
    static final String TEMPLATE = "skycloak-magic-link-email.ftl";
    static final String SUBJECT_KEY = "magicLinkEmailSubject";

    private MagicLinkEmail() {
    }

    static void send(KeycloakSession session, RealmModel realm, UserModel user, String link, String clientName)
            throws EmailException {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("link", link);
        attrs.put("clientName", clientName);
        try {
            session.getProvider(EmailTemplateProvider.class)
                    .setRealm(realm)
                    .setUser(user)
                    .send(SUBJECT_KEY, TEMPLATE, attrs);
            return;
        } catch (Exception e) {
            LOG.debug("themed magic-link email unavailable, using built-in body", e);
        }
        String subject = "Your sign-in link";
        String text = "Sign in to " + clientName + ":\n\n" + link
                + "\n\nThis link expires shortly and can be used once. "
                + "If you did not request it you can ignore this email.\n";
        String html = "<p>Sign in to <strong>" + escape(clientName) + "</strong>:</p>"
                + "<p><a href=\"" + escape(link) + "\">Sign in</a></p>"
                + "<p>This link expires shortly and can be used once. "
                + "If you did not request it you can ignore this email.</p>";
        session.getProvider(EmailSenderProvider.class)
                .send(realm.getSmtpConfig(), user.getEmail(), subject, text, html);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
