package com.scivicslab.lxdpups.model;

import java.util.Map;

/**
 * Per-image health check configuration.
 *
 * Defines what kind of health check to perform (TCP or HTTP),
 * which port and path to check, and timing parameters.
 */
public record HealthCheckConfig(
        String image,
        HealthCheckType type,
        int port,
        String path,
        int intervalMs,
        int maxAttempts
) {

    public enum HealthCheckType {
        TCP,
        HTTP
    }

    private static final int DEFAULT_INTERVAL_MS = 2000;
    private static final int DEFAULT_MAX_ATTEMPTS = 30;

    private static final Map<String, HealthCheckConfig> CONFIGS = Map.of(
            "lxd-pups/ai-tools", new HealthCheckConfig(
                    "lxd-pups/ai-tools", HealthCheckType.TCP, 15888, null,
                    DEFAULT_INTERVAL_MS, DEFAULT_MAX_ATTEMPTS),
            "lxd-pups/claude", new HealthCheckConfig(
                    "lxd-pups/claude", HealthCheckType.TCP, 16120, null,
                    DEFAULT_INTERVAL_MS, DEFAULT_MAX_ATTEMPTS),
            "lxd-pups/jupyter", new HealthCheckConfig(
                    "lxd-pups/jupyter", HealthCheckType.TCP, 16900, null,
                    DEFAULT_INTERVAL_MS, DEFAULT_MAX_ATTEMPTS),
            "lxd-pups/guacamole", new HealthCheckConfig(
                    "lxd-pups/guacamole", HealthCheckType.TCP, 16901, null,
                    DEFAULT_INTERVAL_MS, DEFAULT_MAX_ATTEMPTS)
    );

    /**
     * Returns the health check configuration for the given image.
     * Returns a default TCP-based config on port 15888 for unknown images.
     */
    public static HealthCheckConfig forImage(String image) {
        HealthCheckConfig config = CONFIGS.get(image);
        if (config != null) {
            return config;
        }
        return new HealthCheckConfig(image, HealthCheckType.TCP, 15888, null,
                DEFAULT_INTERVAL_MS, DEFAULT_MAX_ATTEMPTS);
    }

    /**
     * Returns the total timeout in milliseconds (interval * maxAttempts).
     */
    public int totalTimeoutMs() {
        return intervalMs * maxAttempts;
    }
}
