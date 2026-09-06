package com.scivicslab.aiworkspace.config;

import org.eclipse.microprofile.config.ConfigProvider;

/**
 * The port this portal is actually listening on.
 *
 * <p>The port can be given four ways, and only one of them is a system property: an explicit
 * {@code -Dquarkus.http.port}, a range through {@code -Dai-workspace.port-range} (see
 * {@link PortRangeConfigSource}), the {@code QUARKUS_HTTP_PORT} environment variable, or
 * {@code application.properties}. Reading {@code System.getProperty("quarkus.http.port")} sees the
 * first of those and nothing else, and falls back to a literal 28000 for the rest.
 *
 * <p>That was measured: started with {@code -Dai-workspace.port-range=18500-18550} the portal
 * listened on 18500, while the startup banner, the Settings screen, and the {@code AI_WORKSPACE_URL}
 * handed to every tool it launched all said 28000. A tool that believed it would have asked a port
 * with nothing behind it.
 *
 * <p>Resolved configuration answers all four. This is a static helper rather than an injected
 * value because {@code ProcessSupervisor} is constructed directly rather than by the container.
 */
public final class PortalPort {

    /** Used when the configuration does not state a port, which is also what application.properties says. */
    private static final int FALLBACK = 28000;

    private PortalPort() {}

    /** The port this portal listens on. */
    public static int number() {
        return ConfigProvider.getConfig()
                .getOptionalValue("quarkus.http.port", Integer.class)
                .orElse(FALLBACK);
    }

    /**
     * Where to reach this portal from a process on this machine.
     *
     * <p>{@code localhost} because every tool the JVM backend starts is a child process here.
     *
     * @return for example {@code http://localhost:28000}
     */
    public static String baseUrl() {
        return "http://localhost:" + number();
    }
}
