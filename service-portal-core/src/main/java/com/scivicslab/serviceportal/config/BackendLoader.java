package com.scivicslab.serviceportal.config;

import com.scivicslab.serviceportal.spi.ServiceBackend;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Loads the appropriate ServiceBackend implementation based on configuration and environment.
 */
public class BackendLoader {

    private static final Logger logger = Logger.getLogger(BackendLoader.class.getName());

    private static final Map<String, String> BACKEND_CLASSES = Map.of(
        "jvm",          "com.scivicslab.serviceportal.backend.docker.DockerBackend",
        "docker",       "com.scivicslab.serviceportal.backend.docker.DockerBackend",
        "multi-docker", "com.scivicslab.serviceportal.backend.docker.MultiDockerBackend"
    );

    /**
     * Load backend based on configuration.
     */
    public static ServiceBackend loadBackend(ServicePortalConfig config) {
        String backendType = detectBackendType(config);
        logger.info("Selected backend: " + backendType);

        String className = BACKEND_CLASSES.get(backendType);
        if (className == null) {
            throw new IllegalStateException("No backend found for type: " + backendType);
        }
        try {
            ServiceBackend backend = (ServiceBackend) Class.forName(className)
                .getDeclaredConstructor()
                .newInstance();
            backend.initialize(config);
            return backend;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to instantiate backend: " + className, e);
        }
    }

    /**
     * Detect backend type from config or environment.
     */
    private static String detectBackendType(ServicePortalConfig config) {
        // 設定ファイルで明示的に指定されている場合
        if (config.backend() != null && !config.backend().equals("auto")) {
            return config.backend();
        }

        // Docker コンテナ内かチェック
        if (Files.exists(Path.of("/.dockerenv"))) {
            logger.info("Detected Docker container environment");
            return "docker";
        }

        // lxc コマンドが使えるかチェック
        if (isCommandAvailable("lxc")) {
            logger.info("Detected LXC/LXD environment");
            return "lxd";
        }

        // デフォルト
        logger.info("No specific environment detected, defaulting to docker");
        return "docker";
    }

    /**
     * Check if a command is available in PATH.
     */
    private static boolean isCommandAvailable(String command) {
        try {
            Process process = new ProcessBuilder("which", command)
                .redirectErrorStream(true)
                .start();

            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
