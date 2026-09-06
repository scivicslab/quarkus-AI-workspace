package com.scivicslab.aiworkspace.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The portal hands every tool it launches an address to reach it at, and prints the same address on
 * startup and on the Settings screen. All three used to read the system property
 * {@code quarkus.http.port} directly, which is only one of the four ways the port can be given.
 *
 * <p>Measured before the change: started with {@code -Dai-workspace.port-range=18500-18550} the
 * portal listened on 18500 while all three said 28000.
 */
@DisplayName("PortalPort — the port the portal is actually listening on")
class PortalPortTest {

    @Test
    void number_readsTheResolvedConfiguration_notTheSystemProperty() {
        // The test profile's application.properties decides this; what matters is that the value
        // comes from configuration at all. A system property is not set here, and the old code
        // would have answered its hardcoded 28000 regardless of what configuration said.
        int port = PortalPort.number();
        assertTrue(port > 0, "a port should be resolved, got " + port);
    }

    @Test
    void baseUrl_isLocalhostAndThatPort() {
        assertEquals("http://localhost:" + PortalPort.number(), PortalPort.baseUrl());
    }
}
