package io.skycloak.keycloak.magiclink;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 hex helper shared by the token store and the per-email rate-limit key. */
final class Hashing {

    private static final HexFormat HEX = HexFormat.of();

    private Hashing() {
    }

    /** Lower-case hex SHA-256 of the UTF-8 bytes of {@code value}. */
    static String sha256hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory on every supported JVM.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
