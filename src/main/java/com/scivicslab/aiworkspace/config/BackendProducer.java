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

    @jakarta.inject.Inject
    com.scivicslab.aiworkspace.actor.AiWorkspaceActorSystem actors;

    @Produces
    @ApplicationScoped
    public ServiceBackend produceBackend() {
        ServiceBackend backend = BackendLoader.loadBackend();
        // The JVM backend wraps each instance's supervisor in an actor, and is built with new
        // rather than by the container, so it is handed the actor system here
        // (ActorBasedState_260907_oo01).
        if (backend instanceof com.scivicslab.aiworkspace.backend.jvm.JvmBackend jvm) {
            jvm.setActorSystem(actors);
        }
        // After the actor system, not before: initialize() scans for instances already running,
        // and puts what it adopts into the registry actor.
        backend.initialize(BackendLoader.loadConfig());
        logger.info("Produced backend: " + backend.getBackendType());
        return backend;
    }
}
