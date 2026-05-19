package com.scivicslab.aiworkspace.config;

import com.scivicslab.aiworkspace.backend.jvm.JvmBackend;
import com.scivicslab.aiworkspace.spi.ServiceBackend;

import java.util.logging.Logger;

/**
 * Loads the ServiceBackend implementation based on configuration.
 *
 * Only one backend is supported: JvmBackend, which manages
 * java -jar child processes for the tools of a single AI team.
 */
public class BackendLoader {

    private static final Logger logger = Logger.getLogger(BackendLoader.class.getName());

    /**
     * Load and initialize the backend from configuration.
     */
    public static ServiceBackend loadBackend(AiWorkspaceConfig config) {
        logger.info("Selected backend: jvm");
        ServiceBackend backend = new JvmBackend();
        backend.initialize(config);
        return backend;
    }
}
