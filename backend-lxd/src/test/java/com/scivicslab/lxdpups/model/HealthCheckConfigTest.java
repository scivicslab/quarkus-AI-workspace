package com.scivicslab.lxdpups.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HealthCheckConfigTest {

    @Test
    void aiToolsUseTcpOn15888() {
        HealthCheckConfig config = HealthCheckConfig.forImage("lxd-pups/ai-tools");
        assertEquals(HealthCheckConfig.HealthCheckType.TCP, config.type());
        assertEquals(15888, config.port());
        assertNull(config.path());
    }

    @Test
    void claudeUseTcpOn16120() {
        HealthCheckConfig config = HealthCheckConfig.forImage("lxd-pups/claude");
        assertEquals(HealthCheckConfig.HealthCheckType.TCP, config.type());
        assertEquals(16120, config.port());
        assertNull(config.path());
    }

    @Test
    void jupyterUseTcpOn16900() {
        HealthCheckConfig config = HealthCheckConfig.forImage("lxd-pups/jupyter");
        assertEquals(HealthCheckConfig.HealthCheckType.TCP, config.type());
        assertEquals(16900, config.port());
    }

    @Test
    void guacamoleUseTcpOn16901() {
        HealthCheckConfig config = HealthCheckConfig.forImage("lxd-pups/guacamole");
        assertEquals(HealthCheckConfig.HealthCheckType.TCP, config.type());
        assertEquals(16901, config.port());
    }

    @Test
    void unknownImageDefaultsToTcpOn15888() {
        HealthCheckConfig config = HealthCheckConfig.forImage("some-unknown/image");
        assertEquals(HealthCheckConfig.HealthCheckType.TCP, config.type());
        assertEquals(15888, config.port());
        assertNull(config.path());
        assertEquals("some-unknown/image", config.image());
    }

    @Test
    void defaultIntervalIs2Seconds() {
        HealthCheckConfig config = HealthCheckConfig.forImage("lxd-pups/ai-tools");
        assertEquals(2000, config.intervalMs());
    }

    @Test
    void defaultMaxAttemptsIs30() {
        HealthCheckConfig config = HealthCheckConfig.forImage("lxd-pups/ai-tools");
        assertEquals(30, config.maxAttempts());
    }

    @Test
    void totalTimeoutIs60Seconds() {
        HealthCheckConfig config = HealthCheckConfig.forImage("lxd-pups/ai-tools");
        assertEquals(60000, config.totalTimeoutMs());
    }

    @Test
    void allKnownImagesHaveConfigs() {
        String[] knownImages = {
                "lxd-pups/ai-tools",
                "lxd-pups/claude",
                "lxd-pups/jupyter",
                "lxd-pups/guacamole"
        };
        for (String image : knownImages) {
            HealthCheckConfig config = HealthCheckConfig.forImage(image);
            assertEquals(image, config.image(), "Image name mismatch for " + image);
        }
    }
}
