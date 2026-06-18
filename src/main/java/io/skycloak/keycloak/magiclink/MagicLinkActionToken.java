package io.skycloak.keycloak.magiclink;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.keycloak.authentication.actiontoken.DefaultActionToken;

/**
 * Self-contained magic-link action token. It carries the original authorization request's PKCE and
 * OIDC parameters so the click can rebuild an equivalent authentication session and complete it
 * with a PKCE-bound code (see ADR-0001), plus two opaque ids:
 *
 * <ul>
 *   <li>{@code deviceNonce} - matched against the {@code SKYCLOAK_MAGIC_DEVICE} cookie set when the
 *       link was issued, to enforce same-device. Absent when same-device is disabled by config.</li>
 *   <li>{@code consumeId} - the key of the pending-link registry entry, removed atomically on a
 *       successful consume to enforce single-use (and, later, to support admin revoke).</li>
 * </ul>
 *
 * The token is marked repeatable at the framework level ({@code canUseTokenRepeatedly=true}); single
 * use is enforced by the registry, not by Keycloak's built-in action-token single-use, so a link
 * prefetched by an email scanner (which has no device cookie) neither completes nor burns it.
 */
public final class MagicLinkActionToken extends DefaultActionToken {

    public static final String TOKEN_TYPE = "skycloak-magic-link";

    @JsonProperty("rdu")
    private String redirectUri;

    @JsonProperty("scope")
    private String scope;

    @JsonProperty("state")
    private String state;

    @JsonProperty("nce")
    private String nonce;

    @JsonProperty("cc")
    private String codeChallenge;

    @JsonProperty("ccm")
    private String codeChallengeMethod;

    @JsonProperty("rm")
    private String responseMode;

    @JsonProperty("dvc")
    private String deviceNonce;

    @JsonProperty("cid")
    private String consumeId;

    public MagicLinkActionToken(String userId, int absoluteExpirationInSecs, String clientId,
                                String redirectUri, String scope, String state, String nonce,
                                String codeChallenge, String codeChallengeMethod,
                                String responseMode, String deviceNonce, String consumeId) {
        super(userId, TOKEN_TYPE, absoluteExpirationInSecs, UUID.randomUUID());
        this.issuedFor = clientId;
        this.redirectUri = redirectUri;
        this.scope = scope;
        this.state = state;
        this.nonce = nonce;
        this.codeChallenge = codeChallenge;
        this.codeChallengeMethod = codeChallengeMethod;
        this.responseMode = responseMode;
        this.deviceNonce = deviceNonce;
        this.consumeId = consumeId;
    }

    private MagicLinkActionToken() {
        // Required no-arg constructor for JWT deserialization.
    }

    public String getRedirectUri() {
        return redirectUri;
    }

    public String getScope() {
        return scope;
    }

    public String getState() {
        return state;
    }

    public String getNonce() {
        return nonce;
    }

    public String getCodeChallenge() {
        return codeChallenge;
    }

    public String getCodeChallengeMethod() {
        return codeChallengeMethod;
    }

    public String getResponseMode() {
        return responseMode;
    }

    public String getDeviceNonce() {
        return deviceNonce;
    }

    public String getConsumeId() {
        return consumeId;
    }
}
