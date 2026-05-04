package com.scivicslab.serviceportal.e2e;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Shared configuration helpers for E2E test classes.
 */
class E2EConfig {

    static Path configYaml() throws Exception {
        URL url = E2EConfig.class.getClassLoader().getResource("service-portal-test.yaml");
        if (url == null) throw new IllegalStateException("service-portal-test.yaml not found on classpath");
        return Paths.get(url.toURI());
    }

    static Path testJarsDir() throws Exception {
        String override = System.getProperty("test.jars.dir");
        if (override != null) return Path.of(override);

        Path worksDir = Path.of(System.getProperty("user.home"), "works");
        if (Files.exists(worksDir.resolve("quarkus-chat-ui.jar"))
                && Files.exists(worksDir.resolve("html-saurus.jar"))
                && Files.exists(worksDir.resolve("turing-workflow-editor.jar"))) {
            return worksDir;
        }
        throw new IllegalStateException(
                "Tool JARs not found in ~/works. Deploy them first, or set -Dtest.jars.dir=<path>.");
    }

    /**
     * Finds the lowest port base where {@code rangeSize} consecutive ports are all free.
     * Scans from 18080 upward in steps of {@code rangeSize}.
     */
    static int findFreePortBase(int rangeSize) throws IOException {
        for (int base = 18080; base < 20000; base += rangeSize) {
            if (isRangeFree(base, rangeSize)) return base;
        }
        throw new IOException("No free port range of size " + rangeSize + " found between 18080 and 20000");
    }

    /** Finds a single free port, scanning from 19900 upward. */
    static int findFreePort() throws IOException {
        for (int port = 19900; port < 20000; port++) {
            try (ServerSocket ss = new ServerSocket(port)) {
                return port;
            } catch (IOException ignored) {}
        }
        throw new IOException("No free port found between 19900 and 20000");
    }

    private static boolean isRangeFree(int base, int size) {
        for (int p = base; p < base + size; p++) {
            try (ServerSocket ss = new ServerSocket(p)) {
                // free
            } catch (IOException e) {
                return false;
            }
        }
        return true;
    }
}
