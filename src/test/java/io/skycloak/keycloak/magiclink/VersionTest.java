package io.skycloak.keycloak.magiclink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class VersionTest {

    @Test
    void healthVersionMatchesPomVersion() {
        String pomVersion = System.getProperty("project.version");
        assertNotNull(pomVersion, "surefire must pass project.version");
        assertEquals(pomVersion, Version.VERSION,
                "bump Version.VERSION together with the pom version");
    }
}
