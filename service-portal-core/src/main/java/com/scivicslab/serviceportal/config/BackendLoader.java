package com.scivicslab.serviceportal.config;

import com.scivicslab.serviceportal.spi.ServiceBackend;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ServiceLoader;
import java.util.logging.Logger;

/**
 * Loads the appropriate ServiceBackend implementation based on configuration and environment.
 */
public class BackendLoader {

    private static final Logger logger = Logger.getLogger(BackendLoader.class.getName());

    /**
     * Load backend based on configuration.
     */
    public static ServiceBackend loadBackend(ServicePortalConfig config) {
        String backendType = detectBackendType(config);
        logger.info("Selected backend: " + backendType);

        // ServiceLoader で実装を探す
        ServiceLoader<ServiceBackend> loader = ServiceLoader.load(ServiceBackend.class);

        for (ServiceBackend backend : loader) {
            if (backend.getBackendType().equals(backendType)) {
                backend.initialize(config);
                return backend;
            }
        }

        throw new IllegalStateException("No backend found for type: " + backendType);
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
