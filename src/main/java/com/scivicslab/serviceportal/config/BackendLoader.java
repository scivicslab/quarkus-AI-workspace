package com.scivicslab.serviceportal.config;

import com.scivicslab.serviceportal.backend.docker.DockerBackend;
import com.scivicslab.serviceportal.spi.ServiceBackend;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Loads the ServiceBackend implementation based on configuration.
 *
 * Only one backend is supported: DockerBackend (jvm mode), which manages
 * java -jar child processes for the tools of a single AI team.
 */
public class BackendLoader {

    private static final Logger logger = Logger.getLogger(BackendLoader.class.getName());

    /**
     * Load and initialize the backend from configuration.
     */
    public static ServiceBackend loadBackend(ServicePortalConfig config) {
        String backendType = detectBackendType(config);
        logger.info("Selected backend: " + backendType);

        ServiceBackend backend = new DockerBackend();
        backend.initialize(config);
        return backend;
    }

    /**
     * Detect backend type. Currently always returns "jvm".
     * The "jvm" and "docker" aliases both map to DockerBackend.
     */
    private static String detectBackendType(ServicePortalConfig config) {
        if (config.backend() != null && !config.backend().equals("auto")) {
            String b = config.backend();
            if (!b.equals("jvm") && !b.equals("docker")) {
                logger.warning("Unknown backend type '" + b + "', falling back to jvm");
            }
            return "jvm";
        }

        if (Files.exists(Path.of("/.dockerenv"))) {
            logger.info("Detected Docker container environment");
        }

        return "jvm";
    }
}
