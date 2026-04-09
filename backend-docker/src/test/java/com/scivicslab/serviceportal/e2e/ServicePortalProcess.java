package com.scivicslab.serviceportal.e2e;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Path;

/**
 * Starts the service-portal uber-JAR as a child process for integration tests.
 *
 * Required environment variables:
 *   TEST_JARS_DIR  — directory containing html-saurus-vX.Y.Z.jar, quarkus-chat-ui-vX.Y.Z.jar, etc.
 *
 * System properties:
 *   service.portal.jar  — path to the service-portal uber-JAR
 *                         (default: target/backend-docker-1.0.0-runner.jar)
 */
public class ServicePortalProcess {

    private static final String JAR_PROP    = "service.portal.jar";
    private static final String DEFAULT_JAR = "target/backend-docker-1.0.0-runner.jar";

    private final Process process;
    private final int     port;
    private final File    logFile;

    private ServicePortalProcess(Process process, int port, File logFile) {
        this.process = process;
        this.port    = port;
        this.logFile = logFile;
    }

    public static ServicePortalProcess start(Path configYaml, int port) throws Exception {
        requireEnv("TEST_JARS_DIR");

        String jarPath = System.getProperty(JAR_PROP, DEFAULT_JAR);
        File   jarFile = new File(jarPath);
        if (!jarFile.exists()) {
            throw new IllegalStateException(
                "service-portal JAR not found: " + jarFile.getAbsolutePath()
                + " — run 'mvn package -DskipTests' first");
        }

        File logFile = File.createTempFile("service-portal-", ".log");
        logFile.deleteOnExit();

        ProcessBuilder pb = new ProcessBuilder(
            "java",
            "-Dquarkus.http.port=" + port,
            "-Dservice.portal.config=" + configYaml.toAbsolutePath(),
            "-jar", jarFile.getAbsolutePath()
        );
        pb.directory(configYaml.getParent().toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(logFile);

        Process proc = pb.start();
        ServicePortalProcess spp = new ServicePortalProcess(proc, port, logFile);
        spp.waitForReady(20_000);
        return spp;
    }

    private void waitForReady(long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException(
                    "service-portal died during startup. Log: " + logFile.getAbsolutePath());
            }
            try {
                HttpURLConnection conn = (HttpURLConnection)
                    new URL("http://localhost:" + port + "/api/status").openConnection();
                conn.setConnectTimeout(500);
                conn.setReadTimeout(500);
                if (conn.getResponseCode() == 200) return;
            } catch (IOException ignored) {
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException(
            "service-portal did not respond within " + timeoutMs + "ms");
    }

    public int port() { return port; }

    public void stop() {
        process.destroyForcibly();
    }

    // ---------------------------------------------------------------

    private static void requireEnv(String name) {
        String val = System.getenv(name);
        if (val == null || val.isBlank()) {
            throw new IllegalStateException("Environment variable not set: " + name);
        }
    }
}
