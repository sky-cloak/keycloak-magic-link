package io.skycloak.keycloak.magiclink;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;

import org.jboss.logging.Logger;
import org.keycloak.OAuth2Constants;
import org.keycloak.authentication.AuthenticationProcessor;
import org.keycloak.common.ClientConnection;
import org.keycloak.common.util.Time;
import org.keycloak.email.EmailException;
import org.keycloak.email.EmailSenderProvider;
import org.keycloak.events.EventBuilder;
import org.keycloak.events.EventType;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.protocol.oidc.utils.RedirectUtils;
import org.keycloak.services.managers.AppAuthManager;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.services.managers.AuthenticationManager.AuthResult;
import org.keycloak.services.managers.AuthenticationSessionManager;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.sessions.RootAuthenticationSessionModel;
import org.keycloak.util.JsonSerialization;

/**
 * Admin REST API and Mode B consume mounted at {@code /realms/{realm}/skycloak-magic-link} (ADR-0002).
 *
 * <ul>
 *   <li>{@code POST /}        - issue a link for a user. Requires realm-management {@code manage-users}
 *       (override per realm via the {@code skycloak.magic-link.issue-role} attribute). Emails the link,
 *       or returns it when {@code send=false}.</li>
 *   <li>{@code DELETE /{id}}  - revoke a pending link by the id returned at issue.</li>
 *   <li>{@code GET  /consume} - confirm page for a Mode B link (does NOT burn the link).</li>
 *   <li>{@code POST /consume} - burns the link and completes the login (302 with a code).</li>
 *   <li>{@code GET  /health}  - public liveness.</li>
 * </ul>
 *
 * Mode B links carry no PKCE and no device cookie, so consume is two-step (ADR-0004): the emailed link
 * is a GET that shows the confirm page without burning, and only the human POST completes. Keycloak's
 * action-token endpoint is GET-only, so Mode B uses this resource's own consume rather than that
 * handler (which serves the same-device single-step Mode A flow). The raw link and token are never
 * logged. Issuance is account-takeover-equivalent, hence the admin gate.
 */
public final class MagicLinkResource {

    private static final Logger LOG = Logger.getLogger(MagicLinkResource.class);
    private static final String JSON = MediaType.APPLICATION_JSON;

    static final String REALM_MANAGEMENT_CLIENT = "realm-management";
    static final String DEFAULT_ISSUE_ROLE = "manage-users";
    static final String ISSUE_ROLE_ATTRIBUTE = "skycloak.magic-link.issue-role";
    static final int DEFAULT_LIFESPAN_SECONDS = 600;
    static final int PER_EMAIL_PER_MINUTE = 10;

    private final KeycloakSession session;

    public MagicLinkResource(KeycloakSession session) {
        this.session = session;
    }

    @GET
    @Path("health")
    @Produces(JSON)
    public Response health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("name", Version.NAME);
        body.put("version", Version.VERSION);
        body.put("active", true);
        return json(Response.Status.OK, write(body));
    }

    @POST
    @Consumes(JSON)
    @Produces(JSON)
    public Response issue(String rawBody) {
        RealmModel realm = realm();
        requireIssueRole(realm, authenticate(realm));

        IssueRequest req;
        try {
            req = JsonSerialization.readValue(rawBody == null ? "{}" : rawBody, IssueRequest.class);
        } catch (Exception e) {
            return json(Response.Status.BAD_REQUEST, err("invalid_request", "malformed JSON body"));
        }
        if (req == null || blank(req.clientId) || blank(req.redirectUri)) {
            return json(Response.Status.BAD_REQUEST,
                    err("invalid_request", "clientId and redirectUri are required"));
        }

        UserModel user = resolveUser(realm, req);
        if (user == null) {
            return json(Response.Status.NOT_FOUND, err("user_not_found", "no user matched the identifier"));
        }
        ClientModel client = realm.getClientByClientId(req.clientId);
        if (client == null || !client.isEnabled()) {
            return json(Response.Status.BAD_REQUEST, err("unknown_client", "clientId is unknown or disabled"));
        }
        String validatedRedirect = RedirectUtils.verifyRedirectUri(session, req.redirectUri, client);
        if (validatedRedirect == null) {
            return json(Response.Status.BAD_REQUEST,
                    err("invalid_redirect_uri", "redirectUri is not registered for the client"));
        }

        boolean send = req.send == null || req.send; // default true
        if (send) {
            if (blank(user.getEmail())) {
                return json(Response.Status.CONFLICT, err("user_has_no_email", "the user has no email address"));
            }
            // Defense in depth: cap repeated sends per target email even from a trusted caller.
            MagicLinkRateLimiter.Decision rl = new MagicLinkRateLimiter(session)
                    .check(null, normalize(user.getEmail()), 0, PER_EMAIL_PER_MINUTE);
            if (!rl.allowed()) {
                return Response.status(429)
                        .header("Retry-After", String.valueOf(rl.retryAfterSeconds()))
                        .type(JSON).entity(err("rate_limited", "too many links for this user; retry later"))
                        .build();
            }
        }

        int lifespan = (req.expirationSeconds != null && req.expirationSeconds > 0)
                ? req.expirationSeconds : DEFAULT_LIFESPAN_SECONDS;
        String consumeId = UUID.randomUUID().toString();

        // Mode B token: no device nonce (cross-device, admin-issued) and no PKCE (no originating
        // client request). Consume is this resource's own two-step GET/POST below.
        MagicLinkActionToken token = new MagicLinkActionToken(
                user.getId(), Time.currentTime() + lifespan, req.clientId,
                validatedRedirect, req.scope, req.state, null, null, null, null, null, consumeId);

        MagicLinkPendingStore.register(session, consumeId, lifespan, user.getId(), req.clientId);

        String link = buildConsumeLink(realm, token);

        if (send) {
            try {
                sendEmail(realm, user.getEmail(), link, clientName(client));
            } catch (Exception e) {
                LOG.warnf("magic-link admin issue: email send failed id=%s: %s", consumeId, e.getMessage());
                return json(Response.Status.BAD_GATEWAY, err("email_send_failed", "the link could not be emailed"));
            }
            LOG.infof("magic-link admin issue: emailed user=%s client=%s id=%s", user.getId(), req.clientId, consumeId);
        } else {
            LOG.infof("magic-link admin issue: returned-to-caller user=%s client=%s id=%s",
                    user.getId(), req.clientId, consumeId);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", consumeId);
        body.put("expiresAt", Time.currentTime() + lifespan);
        if (!send) {
            body.put("link", link); // returned only when the caller delivers it themselves
        }
        return json(Response.Status.CREATED, write(body));
    }

    @DELETE
    @Path("{id}")
    @Produces(JSON)
    public Response revoke(@PathParam("id") String id) {
        RealmModel realm = realm();
        requireIssueRole(realm, authenticate(realm));
        if (blank(id) || !MagicLinkPendingStore.revoke(session, id)) {
            return json(Response.Status.NOT_FOUND, err("not_found", "no pending link with that id"));
        }
        LOG.infof("magic-link admin revoke: id=%s", id);
        return Response.noContent().build();
    }

    /** Confirm page for a Mode B link. A GET (incl. an email scanner prefetch) never burns the link. */
    @GET
    @Path("consume")
    @Produces(MediaType.TEXT_HTML)
    public Response consumeConfirm(@QueryParam("key") String key) {
        MagicLinkActionToken token = decode(key);
        if (token == null) {
            return htmlError(Response.Status.BAD_REQUEST,
                    "This sign-in link is invalid, has expired, or has already been used.");
        }
        RealmModel realm = realm();
        ClientModel client = realm.getClientByClientId(token.getIssuedFor());
        String action = session.getContext().getUri().getRequestUri().toString();
        String html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>Sign in</title></head>"
                + "<body style=\"font-family:sans-serif;max-width:32rem;margin:4rem auto;text-align:center\">"
                + "<p>Continue signing in to <strong>" + escape(clientName(client)) + "</strong>?</p>"
                + "<form method=\"POST\" action=\"" + escape(action) + "\">"
                + "<button type=\"submit\" style=\"font-size:1rem;padding:.6rem 1.4rem;cursor:pointer\">"
                + "Sign in</button></form></body></html>";
        return Response.ok(html, MediaType.TEXT_HTML).build();
    }

    /** Burns the link and completes the login. Only reached by the human POST from the confirm page. */
    @POST
    @Path("consume")
    public Response consumeComplete(@QueryParam("key") String key) {
        MagicLinkActionToken token = decode(key);
        if (token == null || !MagicLinkPendingStore.consume(session, token.getConsumeId())) {
            return htmlError(Response.Status.BAD_REQUEST,
                    "This sign-in link is invalid, has expired, or has already been used.");
        }
        RealmModel realm = realm();
        ClientModel client = realm.getClientByClientId(token.getIssuedFor());
        if (client == null || !client.isEnabled()) {
            return htmlError(Response.Status.BAD_REQUEST, "The application is unknown or disabled.");
        }
        UserModel user = session.users().getUserById(realm, token.getUserId());
        if (user == null || !user.isEnabled()) {
            return htmlError(Response.Status.UNAUTHORIZED, "The account is unavailable.");
        }
        user.setEmailVerified(true); // clicking a link proves control of the address (ADR-0004)
        return completeLogin(realm, client, user, token);
    }

    /**
     * Verifies the action-token JWT (signature + expiry) and that it is a Mode B token. Returns null
     * when invalid. A same-device (Mode A) token carries a device nonce and MUST be consumed via the
     * action-token handler, which enforces the device cookie; accepting one at this cookieless
     * endpoint would let an intercepted Mode A link bypass same-device.
     */
    private MagicLinkActionToken decode(String key) {
        if (blank(key)) {
            return null;
        }
        try {
            MagicLinkActionToken token = session.tokens().decode(key, MagicLinkActionToken.class);
            if (token == null || !token.isActive() || token.getDeviceNonce() != null) {
                return null;
            }
            return token;
        } catch (Exception e) {
            return null;
        }
    }

    /** Fresh authentication session + user session, then OIDC's success path (issues the code). */
    private Response completeLogin(RealmModel realm, ClientModel client, UserModel user, MagicLinkActionToken token) {
        ClientConnection connection = session.getContext().getConnection();
        AuthenticationSessionManager mgr = new AuthenticationSessionManager(session);
        RootAuthenticationSessionModel root = mgr.createAuthenticationSession(realm, true);
        AuthenticationSessionModel authSession = root.createAuthenticationSession(client);

        String redirect = RedirectUtils.verifyRedirectUri(session, token.getRedirectUri(), client);
        if (redirect == null) {
            return htmlError(Response.Status.BAD_REQUEST, "The return URL is not allowed for this application.");
        }
        authSession.setProtocol(OIDCLoginProtocol.LOGIN_PROTOCOL);
        authSession.setAction(AuthenticationSessionModel.Action.AUTHENTICATE.name());
        authSession.setClientNote(OIDCLoginProtocol.RESPONSE_TYPE_PARAM, "code");
        authSession.setClientNote(OIDCLoginProtocol.REDIRECT_URI_PARAM, token.getRedirectUri());
        authSession.setClientNote(OIDCLoginProtocol.ISSUER,
                session.getContext().getUri().getBaseUri().toString() + "realms/" + realm.getName());
        authSession.setRedirectUri(redirect);
        if (token.getState() != null) {
            authSession.setClientNote(OIDCLoginProtocol.STATE_PARAM, token.getState());
        }
        if (token.getNonce() != null) {
            authSession.setClientNote(OIDCLoginProtocol.NONCE_PARAM, token.getNonce());
        }
        if (token.getScope() != null) {
            authSession.setClientNote(OAuth2Constants.SCOPE, token.getScope());
        }
        authSession.setAuthenticatedUser(user);

        EventBuilder event = new EventBuilder(realm, session, connection)
                .event(EventType.LOGIN).client(client).user(user)
                .detail("auth_method", MagicLinkActionToken.TOKEN_TYPE);

        UserSessionModel userSession = session.sessions().createUserSession(
                authSession.getParentSession().getId(), realm, user, user.getUsername(),
                connection != null ? connection.getRemoteAddr() : null, MagicLinkActionToken.TOKEN_TYPE,
                false, null, null, UserSessionModel.SessionPersistenceState.PERSISTENT);

        ClientSessionContext clientSessionCtx =
                AuthenticationProcessor.attachSession(authSession, userSession, session, realm, connection, event);

        Response response = AuthenticationManager.redirectAfterSuccessfulFlow(
                session, realm, userSession, clientSessionCtx,
                session.getContext().getHttpRequest(), session.getContext().getUri(), connection, event, authSession);
        event.success();
        return response;
    }

    private AuthResult authenticate(RealmModel realm) {
        AuthResult auth = new AppAuthManager.BearerTokenAuthenticator(session)
                .setRealm(realm)
                .setConnection(session.getContext().getConnection())
                .setHeaders(session.getContext().getRequestHeaders())
                .setUriInfo(session.getContext().getUri())
                .authenticate();
        if (auth == null) {
            throw new NotAuthorizedException("Bearer");
        }
        return auth;
    }

    private void requireIssueRole(RealmModel realm, AuthResult auth) {
        String roleName = realm.getAttribute(ISSUE_ROLE_ATTRIBUTE);
        if (roleName == null || roleName.isBlank()) {
            roleName = DEFAULT_ISSUE_ROLE;
        }
        ClientModel rm = realm.getClientByClientId(REALM_MANAGEMENT_CLIENT);
        RoleModel role = rm == null ? null : rm.getRole(roleName);
        if (role == null || auth.getUser() == null || !auth.getUser().hasRole(role)) {
            throw new ForbiddenException("requires realm-management role: " + roleName);
        }
    }

    private UserModel resolveUser(RealmModel realm, IssueRequest req) {
        if (!blank(req.userId)) {
            return session.users().getUserById(realm, req.userId);
        }
        if (!blank(req.email)) {
            return session.users().getUserByEmail(realm, normalize(req.email));
        }
        if (!blank(req.username)) {
            return session.users().getUserByUsername(realm, req.username);
        }
        return null;
    }

    private String buildConsumeLink(RealmModel realm, MagicLinkActionToken token) {
        URI base = session.getContext().getUri().getBaseUri();
        String tokenString = token.serialize(session, realm, session.getContext().getUri());
        return UriBuilder.fromUri(base)
                .path("realms").path(realm.getName()).path(MagicLinkResourceProviderFactory.ID).path("consume")
                .queryParam("key", tokenString)
                .build()
                .toString();
    }

    private void sendEmail(RealmModel realm, String to, String link, String clientName) throws EmailException {
        String subject = "Your sign-in link";
        String text = "Sign in to " + clientName + ":\n\n" + link
                + "\n\nThis link expires shortly and can be used once.\n";
        String html = "<p>Sign in to <strong>" + escape(clientName) + "</strong>:</p>"
                + "<p><a href=\"" + escape(link) + "\">Sign in</a></p>"
                + "<p>This link expires shortly and can be used once.</p>";
        EmailSenderProvider sender = session.getProvider(EmailSenderProvider.class);
        sender.send(realm.getSmtpConfig(), to, subject, text, html);
    }

    private RealmModel realm() {
        RealmModel realm = session.getContext().getRealm();
        if (realm == null) {
            throw new jakarta.ws.rs.NotFoundException("realm not found");
        }
        return realm;
    }

    private static String clientName(ClientModel client) {
        if (client == null) {
            return "the application";
        }
        return client.getName() != null && !client.getName().isBlank() ? client.getName() : client.getClientId();
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String write(Object value) {
        try {
            return JsonSerialization.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"status\":\"error\"}";
        }
    }

    private static String err(String code, String description) {
        return "{\"error\":\"" + code + "\",\"error_description\":\"" + escape(description) + "\"}";
    }

    private static Response json(Response.Status status, String body) {
        return Response.status(status).type(JSON).entity(body).build();
    }

    private static Response htmlError(Response.Status status, String message) {
        return Response.status(status).type(MediaType.TEXT_HTML)
                .entity("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>Sign-in link</title></head>"
                        + "<body style=\"font-family:sans-serif;max-width:32rem;margin:4rem auto\"><p>"
                        + escape(message) + "</p></body></html>")
                .build();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /** Wire format for {@code POST /skycloak-magic-link}. */
    public static final class IssueRequest {
        public String userId;
        public String email;
        public String username;
        public String clientId;
        public String redirectUri;
        public Integer expirationSeconds;
        public Boolean send;
        public String scope;
        public String state;
    }
}
