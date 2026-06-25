package com.scivicslab.aiworkspace.config;

import com.scivicslab.aiworkspace.spi.ServiceBackend;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import java.util.logging.Logger;

/**
 * CDI producer for ServiceBackend.
 */
public class BackendProducer {

    private static final Logger logger = Logger.getLogger(BackendProducer.class.getName());

    @Produces
    @ApplicationScoped
    public ServiceBackend produceBackend() {
        ServiceBackend backend = BackendLoader.loadBackend();
        logger.info("Produced backend: " + backend.getBackendType());
        return backend;
    }
}
