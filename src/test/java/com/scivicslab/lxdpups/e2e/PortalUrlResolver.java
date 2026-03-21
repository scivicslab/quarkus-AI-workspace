package com.scivicslab.lxdpups.e2e;

/**
 * Resolves the child portal URL by querying the LXC container IP.
 * Falls back to PORTAL_URL env var or system property if set.
 */
public final class PortalUrlResolver {

    private PortalUrlResolver() {}

    public static String resolve() {
        // 1. System property (e.g. -Dportal.url=...)
        String prop = System.getProperty("portal.url");
        if (prop != null && !prop.isBlank()) return prop;

        // 2. Environment variable
        String env = System.getenv("PORTAL_URL");
        if (env != null && !env.isBlank()) return env;

        // 3. Query LXC container IP
        try {
            var proc = new ProcessBuilder("bash", "-c",
                    "lxc list AI-container-test --format=csv -c 4 | cut -d' ' -f1")
                    .redirectErrorStream(true)
                    .start();
            String ip = new String(proc.getInputStream().readAllBytes()).trim();
            proc.waitFor();
            if (!ip.isBlank()) return "http://" + ip + ":16080";
        } catch (Exception ignored) {
        }

        return "http://localhost:16080";
    }
}
