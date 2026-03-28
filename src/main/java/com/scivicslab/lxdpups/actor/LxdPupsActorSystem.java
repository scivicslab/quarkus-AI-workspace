package com.scivicslab.lxdpups.actor;

import com.scivicslab.lxdpups.service.BuildManager;
import com.scivicslab.lxdpups.service.ContainerManager;
import com.scivicslab.lxdpups.service.StatusPoller;
import com.scivicslab.lxdpups.service.ToolInstanceManager;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.logging.Logger;

/**
 * CDI singleton managing the POJO-actor system for lxd-pups.
 * <p>
 * Creates the actor hierarchy:
 * <pre>
 *   ActorSystem("lxd-pups")
 *     ├─ ContainerSupervisorActor("supervisor")
 *     │    └─ LaunchWorkerActor("launch-{name}") ... (children, created on demand)
 *     └─ ToolSupervisorActor("tool-supervisor")
 * </pre>
 * </p>
 */
@ApplicationScoped
public class LxdPupsActorSystem {

    private static final Logger LOG = Logger.getLogger(LxdPupsActorSystem.class.getName());

    @Inject
    ContainerManager containerManager;

    @Inject
    StatusPoller statusPoller;

    @Inject
    ToolInstanceManager toolInstanceManager;

    @Inject
    BuildManager buildManager;

    private ActorSystem actorSystem;
    private ActorRef<ContainerSupervisorActor> supervisor;
    private ActorRef<ToolSupervisorActor> toolSupervisor;

    @PostConstruct
    void init() {
        actorSystem = new ActorSystem("lxd-pups");

        var supervisorActor = new ContainerSupervisorActor(containerManager);
        supervisorActor.setOnLaunchDone(() -> statusPoller.refresh());
        supervisor = actorSystem.actorOf("supervisor", supervisorActor);

        var toolSupervisorActor = new ToolSupervisorActor(toolInstanceManager, buildManager);
        toolSupervisor = actorSystem.actorOf("tool-supervisor", toolSupervisorActor);

        LOG.info("LxdPupsActorSystem initialized: supervisor and tool-supervisor ready");
    }

    @PreDestroy
    void shutdown() {
        if (actorSystem != null) {
            actorSystem.terminate();
            LOG.info("LxdPupsActorSystem shut down");
        }
    }

    /**
     * Get the container supervisor actor ref.
     * Use tell() for fire-and-forget operations (launch, stop, service-start/stop).
     * Use ask() for queries that need a response (status, list).
     */
    public ActorRef<ContainerSupervisorActor> getSupervisor() {
        return supervisor;
    }

    /**
     * Get the tool supervisor actor ref.
     * Use tell() for all tool operations (launch, stop, build).
     */
    public ActorRef<ToolSupervisorActor> getToolSupervisor() {
        return toolSupervisor;
    }
}
