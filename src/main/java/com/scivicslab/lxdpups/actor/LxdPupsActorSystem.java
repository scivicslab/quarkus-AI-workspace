package com.scivicslab.lxdpups.actor;

import com.scivicslab.lxdpups.config.PortalConfigLoader;
import com.scivicslab.lxdpups.service.BuildManager;
import com.scivicslab.lxdpups.service.ContainerManager;
import com.scivicslab.lxdpups.service.StatusPoller;
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
 *     ├─ ToolSupervisorActor("tool-supervisor")
 *     ├─ ProcessSupervisorActor("process-supervisor")
 *     │    └─ ProcessWorkerActor("process-{name}") ... (children, created on demand)
 *     ├─ BuildSupervisorActor("build-supervisor")
 *     │    └─ BuildWorkerActor("build-{name}") ... (children, created on demand)
 *     └─ StatusActor("status")
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
    BuildManager buildManager;

    @Inject
    PortalConfigLoader configLoader;

    private ActorSystem actorSystem;
    private ActorRef<ContainerSupervisorActor> supervisor;
    private ActorRef<ToolSupervisorActor> toolSupervisor;
    private ActorRef<ProcessSupervisorActor> processSupervisor;
    private ActorRef<BuildSupervisorActor> buildSupervisor;
    private ActorRef<StatusActor> statusActor;

    @PostConstruct
    void init() {
        actorSystem = new ActorSystem("lxd-pups");

        var supervisorActor = new ContainerSupervisorActor(containerManager);
        supervisorActor.setOnLaunchDone(() -> statusPoller.refresh());
        supervisor = actorSystem.actorOf("supervisor", supervisorActor);

        processSupervisor = actorSystem.actorOf("process-supervisor", new ProcessSupervisorActor());

        buildSupervisor = actorSystem.actorOf("build-supervisor", new BuildSupervisorActor(configLoader));

        statusActor = actorSystem.actorOf("status", new StatusActor());

        var toolSupervisorActor = new ToolSupervisorActor(configLoader, processSupervisor, buildManager);
        toolSupervisor = actorSystem.actorOf("tool-supervisor", toolSupervisorActor);

        LOG.info("LxdPupsActorSystem initialized: all actors ready");
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
     */
    public ActorRef<ContainerSupervisorActor> getSupervisor() {
        return supervisor;
    }

    /**
     * Get the tool supervisor actor ref.
     */
    public ActorRef<ToolSupervisorActor> getToolSupervisor() {
        return toolSupervisor;
    }

    /**
     * Get the process supervisor actor ref.
     */
    public ActorRef<ProcessSupervisorActor> getProcessSupervisor() {
        return processSupervisor;
    }

    /**
     * Get the build supervisor actor ref.
     */
    public ActorRef<BuildSupervisorActor> getBuildSupervisor() {
        return buildSupervisor;
    }

    /**
     * Get the status actor ref.
     */
    public ActorRef<StatusActor> getStatusActor() {
        return statusActor;
    }
}
