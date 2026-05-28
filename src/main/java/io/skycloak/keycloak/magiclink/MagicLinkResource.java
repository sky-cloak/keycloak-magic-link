package io.skycloak.keycloak.magiclink;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;

import org.jboss.logging.Logger;
import org.keycloak.common.ClientConnection;
import org.keycloak.email.EmailException;
import org.keycloak.email.EmailSenderProvider;
import org.keycloak.events.EventBuilder;
import org.keycloak.events.EventType;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.protocol.oidc.utils.RedirectUtils;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.services.managers.AuthenticationSessionManager;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.sessions.RootAuthenticationSessionModel;
import org.keycloak.util.JsonSerialization;

/**
 * JAX-RS resource mounted at {@code /realms/{realm}/magic-link}.
 *
 * <ul>
 *   <li>{@code GET  /health}  - public liveness counters.</li>
 *   <li>{@code POST /request} - body: {@code {email, clientId, redirectUri}}. Always returns
 *       {@code 202} regardless of whether the email belongs to a user (no account enumeration).</li>
 *   <li>{@code GET  /consume} - {@code ?token=...&clientId=...&redirectUri=...}. Signs the user
 *       in and {@code 302}-redirects to the requested URI with an OIDC code.</li>
 * </ul>
 */
public final class MagicLinkResource {

    private static final Logger LOG = Logger.getLogger(MagicLinkResource.class);

    private final KeycloakSession session;
    private final MagicLinkConfig config;
    private final MagicLinkTokenizer tokenizer;

    public MagicLinkResource(KeycloakSession session, MagicLinkConfig config, MagicLinkTokenizer tokenizer) {
        this.session = session;
        this.config = config;
        this.tokenizer = tokenizer;
    }

    @GET
    @Path("health")
    @Produces(MediaType.APPLICATION_JSON)
    public Response health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("name", Version.NAME);
        body.put("version", Version.VERSION);
        body.put("active", tokenizer != null);
        body.put("tokenLifespanSeconds", config != null ? config.tokenLifespanSeconds() : 0);
        body.put("tokensPending", tokenizer != null ? tokenizer.pending() : 0);
        body.put("totalIssued", tokenizer != null ? tokenizer.totalIssued() : 0L);
        body.put("totalConsumed", tokenizer != null ? tokenizer.totalConsumed() : 0L);
        return json(Response.Status.OK, write(body));
    }

    @POST
    @Path("request")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response request(String rawBody) {
        if (tokenizer == null) {
            return json(Response.Status.SERVICE_UNAVAILABLE, "{\"error\":\"magic-link not initialized\"}");
        }

        MagicLinkRequest body;
        try {
            body = JsonSerialization.readValue(rawBody == null ? "{}" : rawBody, MagicLinkRequest.class);
        } catch (Exception e) {
            return json(Response.Status.BAD_REQUEST, "{\"error\":\"invalid_request\"}");
        }
        if (body == null || body.email == null || body.email.isBlank()
                || body.clientId == null || body.clientId.isBlank()
                || body.redirectUri == null || body.redirectUri.isBlank()) {
            return json(Response.Status.BAD_REQUEST,
                    "{\"error\":\"invalid_request\","
                            + "\"error_description\":\"email, clientId and redirectUri are required\"}");
        }

        RealmModel realm = session.getContext().getRealm();
        if (realm == null) {
            return json(Response.Status.NOT_FOUND, "{\"error\":\"realm_not_found\"}");
        }

        // Best-effort lookup and issuance. Failures are logged but the response is always
        // 202: the caller must not be able to distinguish "user exists" from "user does not".
        try {
            ClientModel client = realm.getClientByClientId(body.clientId);
            if (client == null || !client.isEnabled()) {
                LOG.debugf("magic-link request: unknown or disabled client_id=%s", body.clientId);
                return accepted();
            }
            String validatedRedirect = RedirectUtils.verifyRedirectUri(session, body.redirectUri, client);
            if (validatedRedirect == null) {
                LOG.debugf("magic-link request: redirect_uri rejected for client_id=%s uri=%s",
                        body.clientId, body.redirectUri);
                return accepted();
            }
            UserModel user = session.users().getUserByEmail(realm, body.email.trim());
            if (user == null || !user.isEnabled()) {
                LOG.debugf("magic-link request: no matching user for email=%s", redact(body.email));
                return accepted();
            }

            String token = tokenizer.issue(
                    user.getId(), body.clientId, validatedRedirect, config.tokenLifespanSeconds());

            String link = buildConsumeUrl(token, body.clientId, validatedRedirect);
            sendEmail(realm, user, link);
            LOG.infof("magic-link issued for user=%s client=%s", user.getId(), body.clientId);
        } catch (Exception e) {
            LOG.warnf("magic-link request failed: %s", e.getMessage());
        }
        return accepted();
    }

    @GET
    @Path("consume")
    public Response consume(@QueryParam("token") String token,
                            @QueryParam("clientId") String clientId,
                            @QueryParam("redirectUri") String redirectUri) {
        if (tokenizer == null) {
            return json(Response.Status.SERVICE_UNAVAILABLE, "{\"error\":\"magic-link not initialized\"}");
        }
        if (token == null || token.isBlank()) {
            return json(Response.Status.BAD_REQUEST, "{\"error\":\"missing_token\"}");
        }

        Optional<MagicLinkTokenizer.Entry> maybe = tokenizer.consume(token);
        if (maybe.isEmpty()) {
            LOG.debug("magic-link consume: token unknown or expired");
            return json(Response.Status.UNAUTHORIZED,
                    "{\"error\":\"invalid_token\","
                            + "\"error_description\":\"the magic link is invalid, expired, or has already been used\"}");
        }
        MagicLinkTokenizer.Entry entry = maybe.get();

        // Caller-supplied clientId / redirectUri are optional - the token already binds them.
        // If supplied, they must match the bound values exactly to prevent open redirects.
        if (clientId != null && !clientId.isBlank() && !clientId.equals(entry.clientId())) {
            return json(Response.Status.BAD_REQUEST, "{\"error\":\"clientId_mismatch\"}");
        }
        if (redirectUri != null && !redirectUri.isBlank() && !redirectUri.equals(entry.redirectUri())) {
            return json(Response.Status.BAD_REQUEST, "{\"error\":\"redirectUri_mismatch\"}");
        }

        RealmModel realm = session.getContext().getRealm();
        if (realm == null) {
            return json(Response.Status.NOT_FOUND, "{\"error\":\"realm_not_found\"}");
        }
        ClientModel client = realm.getClientByClientId(entry.clientId());
        if (client == null || !client.isEnabled()) {
            return json(Response.Status.BAD_REQUEST, "{\"error\":\"unknown_client\"}");
        }
        UserModel user = session.users().getUserById(realm, entry.userId());
        if (user == null || !user.isEnabled()) {
            return json(Response.Status.UNAUTHORIZED, "{\"error\":\"unknown_user\"}");
        }

        return completeLogin(realm, client, user, entry.redirectUri());
    }

    /**
     * Builds a fresh authentication session, attaches a new user session as authenticated, and
     * returns an OIDC redirect to the bound redirect URI carrying an authorization code.
     */
    private Response completeLogin(RealmModel realm, ClientModel client, UserModel user, String redirectUri) {
        ClientConnection connection = session.getContext().getConnection();
        AuthenticationSessionManager sessionManager = new AuthenticationSessionManager(session);
        RootAuthenticationSessionModel root = sessionManager.createAuthenticationSession(realm, true);
        AuthenticationSessionModel authSession = root.createAuthenticationSession(client);

        authSession.setProtocol(OIDCLoginProtocol.LOGIN_PROTOCOL);
        authSession.setAction(AuthenticationSessionModel.Action.AUTHENTICATE.name());
        authSession.setClientNote(OIDCLoginProtocol.RESPONSE_TYPE_PARAM, "code");
        authSession.setClientNote(OIDCLoginProtocol.REDIRECT_URI_PARAM, redirectUri);
        authSession.setClientNote(OIDCLoginProtocol.ISSUER,
                session.getContext().getUri().getBaseUri().toString() + "realms/" + realm.getName());
        authSession.setRedirectUri(redirectUri);
        authSession.setAuthenticatedUser(user);

        EventBuilder event = new EventBuilder(realm, session, connection)
                .event(EventType.LOGIN)
                .client(client)
                .user(user)
                .detail("auth_method", "magic-link");

        UserSessionModel userSession = session.sessions().createUserSession(
                authSession.getParentSession().getId(),
                realm,
                user,
                user.getUsername(),
                connection != null ? connection.getRemoteAddr() : null,
                "magic-link",
                false,
                null,
                null,
                UserSessionModel.SessionPersistenceState.PERSISTENT);

        // Attach the auth session to the user session and produce a client session context.
        ClientSessionContext clientSessionCtx = org.keycloak.authentication.AuthenticationProcessor.attachSession(
                authSession, userSession, session, realm, connection, event);

        // Hand off to OIDC's success path - this issues the code and returns the 302.
        Response response = AuthenticationManager.redirectAfterSuccessfulFlow(
                session, realm, userSession, clientSessionCtx,
                session.getContext().getHttpRequest(), session.getContext().getUri(),
                connection, event, authSession);

        event.success();
        return response;
    }

    private String buildConsumeUrl(String token, String clientId, String redirectUri) {
        URI base = session.getContext().getUri().getBaseUri();
        String realmName = session.getContext().getRealm().getName();
        return UriBuilder.fromUri(base)
                .path("realms").path(realmName).path("magic-link").path("consume")
                .queryParam("token", token)
                .queryParam("clientId", clientId)
                .queryParam("redirectUri", redirectUri)
                .build()
                .toString();
    }

    private void sendEmail(RealmModel realm, UserModel user, String link) {
        Map<String, String> smtpConfig = realm.getSmtpConfig();
        if (smtpConfig == null || smtpConfig.isEmpty()) {
            LOG.warnf("realm %s has no SMTP configured - cannot deliver magic link", realm.getName());
            return;
        }
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            LOG.warnf("user %s has no email - cannot deliver magic link", user.getId());
            return;
        }

        Map<String, String> effective = new LinkedHashMap<>(smtpConfig);
        if (config.fromEmailOverride() != null) {
            effective.put("from", config.fromEmailOverride());
        }

        String subject = config.subjectTemplate();
        String body = config.renderBody(link);

        EmailSenderProvider sender = session.getProvider(EmailSenderProvider.class);
        if (sender == null) {
            LOG.warn("no EmailSenderProvider available; cannot deliver magic link");
            return;
        }
        try {
            sender.send(effective, user.getEmail(), subject, body, null);
        } catch (EmailException e) {
            LOG.warnf("failed to send magic-link email to user=%s: %s", user.getId(), e.getMessage());
        }
    }

    /** Mask local-part of an email for log lines. */
    private static String redact(String email) {
        if (email == null) {
            return null;
        }
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    @SuppressWarnings("unused") // referenced via reflective ID for debug
    private static String newRequestId() {
        return UUID.randomUUID().toString();
    }

    private static String write(Object value) {
        try {
            return JsonSerialization.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"status\":\"error\"}";
        }
    }

    private static Response json(Response.Status status, String body) {
        return Response.status(status).type(MediaType.APPLICATION_JSON).entity(body).build();
    }

    private static Response accepted() {
        return Response.status(Response.Status.ACCEPTED)
                .type(MediaType.APPLICATION_JSON)
                .entity("{\"status\":\"accepted\"}")
                .build();
    }

    /** Wire format for {@code POST /request}. */
    public static final class MagicLinkRequest {
        public String email;
        public String clientId;
        public String redirectUri;
    }
}
