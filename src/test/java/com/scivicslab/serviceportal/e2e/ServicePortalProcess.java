package com.scivicslab.serviceportal.e2e;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

/**
 * Starts the service-portal uber-JAR as a child process for integration tests.
 *
 * Required environment variables:
 *   TEST_JARS_DIR  — directory containing the tool uber-JARs with non-versioned names:
 *                    quarkus-chat-ui.jar, html-saurus.jar, turing-workflow-editor.jar
 *                    (same naming convention as production service-portal.yaml)
 *
 * System properties:
 *   service.portal.jar  — path to the service-portal uber-JAR
 *                         (default: auto-detected from target/service-portal-*-runner.jar)
 */
public class ServicePortalProcess {

    private static final String JAR_PROP = "service.portal.jar";

    private static String resolveDefaultJar() {
        String explicit = System.getProperty(JAR_PROP);
        if (explicit != null) return explicit;
        try {
            Optional<Path> found = Files.list(Paths.get("target"))
                .filter(p -> {
                    String n = p.getFileName().toString();
                    return n.startsWith("quarkus-AI-workspace-") && n.endsWith(".jar");
                })
                .max(Comparator.comparing(p -> p.getFileName().toString()));
            if (found.isPresent()) return found.get().toString();
        } catch (IOException ignored) {
        }
        return "target/quarkus-AI-workspace-runner.jar";
    }

    private final Process process;
    private final int     port;
    private final File    logFile;

    private ServicePortalProcess(Process process, int port, File logFile) {
        this.process = process;
        this.port    = port;
        this.logFile = logFile;
    }

    /**
     * Start service-portal with an explicit jarsDir that overrides TEST_JARS_DIR.
     * Useful when the test prepares a temp dir with correctly-named JARs.
     */
    public static ServicePortalProcess start(Path configYaml, int port,
                                             Map<String, String> extraEnv) throws Exception {
        String jarPath = resolveDefaultJar();
        File   jarFile = new File(jarPath);
        if (!jarFile.exists()) {
            throw new IllegalStateException(
                "service-portal JAR not found: " + jarFile.getAbsolutePath()
                + " — run 'mvn package -DskipTests' first");
        }

        File logFile = new File(System.getProperty("java.io.tmpdir"),
                "service-portal-" + port + ".log");

        // Service-portal resolves relative tool JAR paths against its working directory.
        // Use TEST_JARS_DIR if provided so that tools are found correctly.
        String jarsDir = extraEnv.getOrDefault("TEST_JARS_DIR",
                Path.of(System.getProperty("user.home"), "works").toString());
        File workDir = new File(jarsDir);

        ProcessBuilder pb = new ProcessBuilder(
            "java",
            "-Dquarkus.http.port=" + port,
            "-jar", jarFile.getAbsolutePath()
        );
        pb.directory(workDir);
        extraEnv.forEach((k, v) -> pb.environment().put(k, v));
        pb.redirectErrorStream(true);
        pb.redirectOutput(logFile);

        Process proc = pb.start();
        System.out.println("  [service-portal:" + port + "] log → " + logFile.getAbsolutePath());
        ServicePortalProcess spp = new ServicePortalProcess(proc, port, logFile);
        spp.waitForReady(20_000);
        return spp;
    }

    public static ServicePortalProcess start(Path configYaml, int port) throws Exception {
        requireEnv("TEST_JARS_DIR");
        String testJarsDir = System.getenv("TEST_JARS_DIR");

        String jarPath = resolveDefaultJar();
        File   jarFile = new File(jarPath);
        if (!jarFile.exists()) {
            throw new IllegalStateException(
                "service-portal JAR not found: " + jarFile.getAbsolutePath()
                + " — run 'mvn package -DskipTests' first");
        }

        File logFile = new File(System.getProperty("java.io.tmpdir"),
                "service-portal-" + port + ".log");

        ProcessBuilder pb = new ProcessBuilder(
            "java",
            "-Dquarkus.http.port=" + port,
            "-jar", jarFile.getAbsolutePath()
        );
        pb.directory(new File(testJarsDir));
        pb.environment().put("TEST_JARS_DIR", testJarsDir);
        pb.redirectErrorStream(true);
        pb.redirectOutput(logFile);
        System.out.println("  [service-portal:" + port + "] log → " + logFile.getAbsolutePath());

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

    public File logFile() { return logFile; }

    public void stop() {
        long pid = process.pid();
        // Kill all descendants recursively, then the portal itself.
        killDescendants(pid);
        process.destroyForcibly();
        try { process.waitFor(); } catch (InterruptedException ignored) {}
        // Brief pause to let the OS release bound ports.
        try { Thread.sleep(1_500); } catch (InterruptedException ignored) {}
    }

    private static void killDescendants(long pid) {
        // First recurse into grandchildren, then kill direct children.
        try {
            Process pgrep = new ProcessBuilder("pgrep", "-P", String.valueOf(pid))
                .start();
            String out = new String(pgrep.getInputStream().readAllBytes()).trim();
            pgrep.waitFor();
            if (!out.isEmpty()) {
                for (String childPid : out.split("\\s+")) {
                    try { killDescendants(Long.parseLong(childPid.trim())); }
                    catch (NumberFormatException ignored) {}
                }
            }
        } catch (Exception ignored) {}
        try {
            new ProcessBuilder("kill", "-9", String.valueOf(pid)).start().waitFor();
        } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------

    private static void requireEnv(String name) {
        String val = System.getenv(name);
        if (val == null || val.isBlank()) {
            throw new IllegalStateException("Environment variable not set: " + name);
        }
    }
}
