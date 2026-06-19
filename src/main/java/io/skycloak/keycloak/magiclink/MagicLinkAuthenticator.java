package io.skycloak.keycloak.magiclink;

import java.net.URI;
import java.util.Locale;
import java.util.UUID;

import jakarta.ws.rs.core.NewCookie;

import org.jboss.logging.Logger;
import org.keycloak.OAuth2Constants;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.Authenticator;
import org.keycloak.common.util.Time;
import org.keycloak.models.Constants;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.services.Urls;
import org.keycloak.services.resources.LoginActionsService;
import org.keycloak.services.resources.RealmsResource;
import org.keycloak.sessions.AuthenticationSessionModel;

/**
 * Standalone browser-flow authenticator for passwordless email magic-link sign-in. It renders its
 * own email-entry form (it does not extend the username/password form), or, when a prior step has
 * already resolved the user, skips straight to sending the link.
 *
 * <p>On submit it normalizes the email, applies per-IP and per-email rate limiting, resolves (or,
 * when configured, creates) the user, issues a self-contained action token carrying the original
 * request's PKCE / OIDC parameters, sets a same-device cookie, records the link in the pending
 * registry, emails it, and parks the flow on a "check your email" page. Per ADR-0004 the parked
 * page is identical whether the user exists, does not exist, or was throttled, and the link is only
 * sent for a valid enabled user that is not throttled, so the form is not an enumeration oracle.
 */
public final class MagicLinkAuthenticator implements Authenticator {

    private static final Logger LOG = Logger.getLogger(MagicLinkAuthenticator.class);
    static final String FORM_EMAIL_FIELD = "email";

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        UserModel resolved = context.getUser();
        if (resolved != null) {
            // A prior step already identified the user: skip the prompt and send straight away.
            MagicLinkAuthConfig config = new MagicLinkAuthConfig(context.getAuthenticatorConfig());
            if (eligible(resolved)) {
                issueAndSend(context, resolved, config);
            }
            context.challenge(context.form().createForm("skycloak-magic-link-sent.ftl"));
            return;
        }
        context.challenge(context.form().createForm("skycloak-magic-link-form.ftl"));
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        String email = context.getHttpRequest().getDecodedFormParameters().getFirst(FORM_EMAIL_FIELD);
        if (email == null || email.isBlank()) {
            context.challenge(context.form()
                    .setError("magicLinkMissingEmail")
                    .createForm("skycloak-magic-link-form.ftl"));
            return;
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);

        MagicLinkAuthConfig config = new MagicLinkAuthConfig(context.getAuthenticatorConfig());
        KeycloakSession session = context.getSession();
        RealmModel realm = context.getRealm();

        String clientIp = context.getConnection() != null ? context.getConnection().getRemoteAddr() : null;
        MagicLinkRateLimiter.Decision rl = new MagicLinkRateLimiter(session).check(
                clientIp, normalized, config.requestsPerMinutePerIp(), config.requestsPerMinutePerEmail());

        UserModel user = session.users().getUserByEmail(realm, normalized);
        if (user == null && config.autoCreateUser()) {
            user = createUser(session, realm, normalized);
        }

        // Only send for an eligible user that is not throttled. The parked page below is identical
        // either way, so neither throttling nor a missing account is observable to the caller.
        if (rl.allowed() && eligible(user)) {
            issueAndSend(context, user, config);
        } else {
            LOG.debugf("magic-link: not sending (throttled=%b, eligibleUser=%b)",
                    !rl.allowed(), eligible(user));
        }
        context.challenge(context.form().createForm("skycloak-magic-link-sent.ftl"));
    }

    private static boolean eligible(UserModel user) {
        return user != null && user.isEnabled() && user.getEmail() != null && !user.getEmail().isBlank();
    }

    private static UserModel createUser(KeycloakSession session, RealmModel realm, String email) {
        UserModel user = session.users().addUser(realm, email);
        user.setEnabled(true);
        user.setEmail(email);
        // emailVerified is set true only once the user proves control by consuming the link.
        return user;
    }

    private void issueAndSend(AuthenticationFlowContext context, UserModel user, MagicLinkAuthConfig config) {
        KeycloakSession session = context.getSession();
        RealmModel realm = context.getRealm();
        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        String clientId = session.getContext().getClient().getClientId();
        int lifespan = config.tokenLifespanSeconds();

        String consumeId = UUID.randomUUID().toString();
        String deviceNonce = config.sameDevice() ? UUID.randomUUID().toString() : null;

        MagicLinkActionToken token = new MagicLinkActionToken(
                user.getId(),
                Time.currentTime() + lifespan,
                clientId,
                authSession.getRedirectUri(),
                authSession.getClientNote(OAuth2Constants.SCOPE),
                authSession.getClientNote(OIDCLoginProtocol.STATE_PARAM),
                authSession.getClientNote(OIDCLoginProtocol.NONCE_PARAM),
                authSession.getClientNote(OIDCLoginProtocol.CODE_CHALLENGE_PARAM),
                authSession.getClientNote(OIDCLoginProtocol.CODE_CHALLENGE_METHOD_PARAM),
                authSession.getClientNote(OIDCLoginProtocol.RESPONSE_MODE_PARAM),
                deviceNonce,
                consumeId);

        MagicLinkPendingStore.register(session, consumeId, lifespan, user.getId(), clientId);

        URI baseUri = session.getContext().getUri().getBaseUri();
        String tokenString = token.serialize(session, realm, session.getContext().getUri());
        String link = Urls.realmBase(baseUri)
                .path(RealmsResource.class, "getLoginActionsService")
                .path(LoginActionsService.class, "executeActionToken")
                .queryParam(Constants.KEY, tokenString)
                .queryParam(Constants.CLIENT_ID, clientId)
                .build(realm.getName())
                .toString();

        if (deviceNonce != null) {
            boolean secure = "https".equalsIgnoreCase(baseUri.getScheme());
            NewCookie cookie = new NewCookie.Builder(MagicLinkActionTokenHandler.DEVICE_COOKIE)
                    .value(deviceNonce)
                    .path("/realms/" + realm.getName())
                    .version(1)
                    .httpOnly(true)
                    .secure(secure)
                    .maxAge(lifespan)
                    .sameSite(NewCookie.SameSite.LAX) // Lax (not Strict) so the email-client click sends it
                    .build();
            session.getContext().getHttpResponse().setCookieIfAbsent(cookie);
        }

        try {
            MagicLinkEmail.send(session, realm, user, link, clientDisplayName(session));
        } catch (Exception e) {
            // Anti-enumeration: never surface send failures to the caller. Log and move on.
            LOG.warnf("magic-link: email send failed: %s", e.getMessage());
        }
        LOG.infof("magic-link: issued link user=%s client=%s sameDevice=%b", user.getId(), clientId,
                deviceNonce != null);
    }

    private static String clientDisplayName(KeycloakSession session) {
        var client = session.getContext().getClient();
        if (client == null) {
            return "the application";
        }
        return client.getName() != null && !client.getName().isBlank()
                ? client.getName() : client.getClientId();
    }

    @Override
    public boolean requiresUser() {
        return false;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
    }

    @Override
    public void close() {
    }
}
