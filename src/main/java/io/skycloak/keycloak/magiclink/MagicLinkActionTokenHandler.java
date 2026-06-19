package io.skycloak.keycloak.magiclink;

import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.logging.Logger;
import org.keycloak.OAuth2Constants;
import org.keycloak.authentication.actiontoken.AbstractActionTokenHandler;
import org.keycloak.authentication.actiontoken.ActionTokenContext;
import org.keycloak.events.Details;
import org.keycloak.events.Errors;
import org.keycloak.events.EventType;
import org.keycloak.models.ClientModel;
import org.keycloak.models.UserModel;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.protocol.oidc.utils.RedirectUtils;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.services.messages.Messages;
import org.keycloak.sessions.AuthenticationSessionModel;

/**
 * Completes a magic-link sign-in. Re-applies the token's PKCE / OIDC parameters onto a fresh session
 * (ADR-0001) and completes the login so the issued code is PKCE-bound.
 *
 * <p><b>Scanner-safe single use without a confirm page.</b> The token is repeatable at the framework
 * level ({@code canUseTokenRepeatedly=true}); single use is enforced by {@link MagicLinkPendingStore}
 * and burned only after the same-device check passes. An email security scanner that prefetches the
 * link runs server-side without the user's {@code SKYCLOAK_MAGIC_DEVICE} cookie, so its GET fails the
 * same-device check and returns without burning the registry entry. The genuine click, from the same
 * browser, carries the cookie, burns the entry once, and completes. This makes the link scanner-safe
 * by construction under same-device, which is the default. (See the Phase 1 report: the ADR-0004
 * two-step confirm page is the compensating control for the opt-in any-device mode, where there is no
 * device cookie to lean on; it is not needed while same-device is on.)
 */
public final class MagicLinkActionTokenHandler
        extends AbstractActionTokenHandler<MagicLinkActionToken> {

    private static final Logger LOG = Logger.getLogger(MagicLinkActionTokenHandler.class);
    public static final String DEVICE_COOKIE = "SKYCLOAK_MAGIC_DEVICE";

    public MagicLinkActionTokenHandler() {
        super(MagicLinkActionToken.TOKEN_TYPE,
                MagicLinkActionToken.class,
                Messages.INVALID_REQUEST,
                EventType.EXECUTE_ACTION_TOKEN,
                Errors.INVALID_REQUEST);
    }

    @Override
    public boolean canUseTokenRepeatedly(MagicLinkActionToken token,
                                         ActionTokenContext<MagicLinkActionToken> ctx) {
        // Single use is enforced by the registry (burned post-same-device-check), not by the
        // framework, so a scanner prefetch without the device cookie cannot burn the link.
        return true;
    }

    @Override
    public AuthenticationSessionModel startFreshAuthenticationSession(
            MagicLinkActionToken token, ActionTokenContext<MagicLinkActionToken> ctx) {
        return ctx.createAuthenticationSessionForClient(token.getIssuedFor());
    }

    @Override
    public Response handleToken(MagicLinkActionToken token,
                                ActionTokenContext<MagicLinkActionToken> ctx) {
        AuthenticationSessionModel authSession = ctx.getAuthenticationSession();
        ClientModel client = authSession.getClient();

        // Same-device: the cookie set when the link was issued must be present and match. A scanner
        // (no cookie) fails here and the registry entry is left intact for the genuine click.
        if (token.getDeviceNonce() != null) {
            String cookieVal = readCookie(ctx, DEVICE_COOKIE);
            if (cookieVal == null || !cookieVal.equals(token.getDeviceNonce())) {
                LOG.warnf("magic-link same-device check failed (cookiePresent=%b)", cookieVal != null);
                ctx.getEvent().event(EventType.LOGIN_ERROR).detail("magic_link_error", "wrong_device")
                        .error(Errors.INVALID_TOKEN);
                return htmlError(Response.Status.FORBIDDEN,
                        "Open this sign-in link in the same browser where you started signing in.");
            }
        }

        // Single use: atomically burn the registry entry. A replay, expiry, or admin revoke fails.
        if (!MagicLinkPendingStore.consume(ctx.getSession(), token.getConsumeId())) {
            LOG.debugf("magic-link consume rejected: registry entry absent (used/expired/revoked)");
            ctx.getEvent().event(EventType.LOGIN_ERROR).detail("magic_link_error", "invalid_or_used")
                    .error(Errors.EXPIRED_CODE);
            return htmlError(Response.Status.BAD_REQUEST,
                    "This sign-in link is invalid, has expired, or has already been used.");
        }

        applyOidcParams(token, ctx, authSession, client);

        UserModel user = authSession.getAuthenticatedUser();
        if (user != null) {
            // Clicking a link delivered to the address proves control of it (ADR-0004).
            user.setEmailVerified(true);
        }
        authSession.setUserSessionNote("login_method", MagicLinkActionToken.TOKEN_TYPE);
        ctx.getEvent().detail(Details.AUTH_METHOD, MagicLinkActionToken.TOKEN_TYPE);

        String nextAction = AuthenticationManager.nextRequiredAction(
                ctx.getSession(), authSession, ctx.getRequest(), ctx.getEvent());
        return AuthenticationManager.redirectToRequiredActions(
                ctx.getSession(), ctx.getRealm(), authSession, ctx.getUriInfo(), nextAction);
    }

    private static void applyOidcParams(MagicLinkActionToken token,
                                        ActionTokenContext<MagicLinkActionToken> ctx,
                                        AuthenticationSessionModel authSession, ClientModel client) {
        String redirect = RedirectUtils.verifyRedirectUri(ctx.getSession(), token.getRedirectUri(), client);
        if (redirect != null) {
            authSession.setAuthNote(AuthenticationManager.SET_REDIRECT_URI_AFTER_REQUIRED_ACTIONS, "true");
            authSession.setRedirectUri(redirect);
            authSession.setClientNote(OIDCLoginProtocol.REDIRECT_URI_PARAM, token.getRedirectUri());
            if (token.getState() != null) {
                authSession.setClientNote(OIDCLoginProtocol.STATE_PARAM, token.getState());
            }
            if (token.getNonce() != null) {
                authSession.setClientNote(OIDCLoginProtocol.NONCE_PARAM, token.getNonce());
                authSession.setUserSessionNote(OIDCLoginProtocol.NONCE_PARAM, token.getNonce());
            }
            if (token.getCodeChallenge() != null) {
                authSession.setClientNote(OIDCLoginProtocol.CODE_CHALLENGE_PARAM, token.getCodeChallenge());
            }
            if (token.getCodeChallengeMethod() != null) {
                authSession.setClientNote(OIDCLoginProtocol.CODE_CHALLENGE_METHOD_PARAM,
                        token.getCodeChallengeMethod());
            }
            if (token.getResponseMode() != null) {
                authSession.setClientNote(OIDCLoginProtocol.RESPONSE_MODE_PARAM, token.getResponseMode());
            }
        }
        if (token.getScope() != null) {
            // Scope note only. We deliberately do NOT call
            // AuthenticationManager.setClientScopesInSession: its signature differs between KC 25
            // (1-arg) and KC 26 (2-arg), and downstream OIDC computes scopes from this note anyway.
            authSession.setClientNote(OAuth2Constants.SCOPE, token.getScope());
        }
    }

    private static Response htmlError(Response.Status status, String message) {
        return Response.status(status)
                .type(MediaType.TEXT_HTML)
                .entity("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>Sign-in link</title>"
                        + "</head><body style=\"font-family:sans-serif;max-width:32rem;margin:4rem auto\">"
                        + "<p>" + escape(message) + "</p></body></html>")
                .build();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String readCookie(ActionTokenContext<MagicLinkActionToken> ctx, String name) {
        try {
            var cookies = ctx.getSession().getContext().getHttpRequest().getHttpHeaders().getCookies();
            if (cookies == null) {
                return null;
            }
            Cookie c = cookies.get(name);
            return c == null ? null : c.getValue();
        } catch (Exception e) {
            return null;
        }
    }
}
