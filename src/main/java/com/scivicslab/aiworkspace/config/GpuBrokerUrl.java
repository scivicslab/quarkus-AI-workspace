package com.scivicslab.aiworkspace.config;

import java.util.Optional;

/**
 * Where the GPU broker answers: the one value {@code start.sh} passes as {@code -Dgpu.broker.url}
 * (or, failing that, the environment's {@code GPU_BROKER_URL}). The portal hands it to every tool it
 * launches, shows it under Settings, and watches the broker at it from the Catalog; all of them read
 * it here, so they cannot disagree ({@code ServiceDirectory_260905_oo01}).
 *
 * <p>Resolved on every call rather than held, so a portal restart is all it takes to move to another
 * broker address.</p>
 */
public final class GpuBrokerUrl {

    private GpuBrokerUrl() {
    }

    /** @return the broker's base URL without a trailing slash, or empty when none is configured */
    public static Optional<String> resolve() {
        String url = System.getProperty("gpu.broker.url");
        if (url == null || url.isBlank()) url = System.getenv("GPU_BROKER_URL");
        if (url == null || url.isBlank()) return Optional.empty();
        return Optional.of(url.trim().replaceAll("/+$", ""));
    }
}
