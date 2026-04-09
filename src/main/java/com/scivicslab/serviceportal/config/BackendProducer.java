package com.scivicslab.serviceportal.config;

import com.scivicslab.serviceportal.spi.ServiceBackend;

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
        ServicePortalConfig config = ServicePortalConfigLoader.load();
        ServiceBackend backend = BackendLoader.loadBackend(config);
        logger.info("Produced backend: " + backend.getBackendType());
        return backend;
    }
}
