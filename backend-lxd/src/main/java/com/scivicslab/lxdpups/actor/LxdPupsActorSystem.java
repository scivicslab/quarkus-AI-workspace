package com.scivicslab.lxdpups.actor;

import com.scivicslab.lxdpups.config.PortalConfigLoader;
import com.scivicslab.lxdpups.model.ContainerLifecycleState;
import com.scivicslab.lxdpups.model.ContainerRecord;
import com.scivicslab.lxdpups.model.HealthCheckConfig;
import com.scivicslab.lxdpups.service.ContainerManager;
import com.scivicslab.lxdpups.service.StatusPoller;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.logging.Logger;

/**
 * CDI singleton managing the POJO-actor system for lxd-pups.
 * <p>
 * Creates the actor hierarchy:
 * <pre>
 *   ActorSystem("lxd-pups")
 *     ├─ ContainerSupervisorActor("supervisor")
 *     │    └─ LaunchWorkerActor("launch-{name}") ... (children, created on demand)
 *     ├─ ProcessSupervisorActor("process-supervisor")
 *     │    └─ ProcessWorkerActor("process-{name}") ... (children, created on demand)
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
    PortalConfigLoader configLoader;

    private ActorSystem actorSystem;
    private ActorRef<ContainerSupervisorActor> supervisor;
    private ActorRef<ProcessSupervisorActor> processSupervisor;
    private ActorRef<StatusActor> statusActor;

    @PostConstruct
    void init() {
        actorSystem = new ActorSystem("lxd-pups");

        var supervisorActor = new ContainerSupervisorActor(containerManager);
        supervisorActor.setOnLaunchDone(() -> statusPoller.refresh());
        supervisor = actorSystem.actorOf("supervisor", supervisorActor);

        processSupervisor = actorSystem.actorOf("process-supervisor", new ProcessSupervisorActor());

        statusActor = actorSystem.actorOf("status", new StatusActor());

        restoreContainerState();

        LOG.info("LxdPupsActorSystem initialized: all actors ready");
    }

    /**
     * Scan existing LXC worker containers and register them in the supervisor.
     * Running containers get a TCP health check — healthy ones become READY, others FAILED.
     * Stopped containers are registered as STOPPED.
     */
    private void restoreContainerState() {
        try {
            var containers = containerManager.list("local");
            if (containers.isEmpty()) {
                LOG.info("No existing worker containers found");
                return;
            }

            int restored = 0;
            for (var c : containers) {
                ContainerLifecycleState state;
                String failureReason = null;

                if ("Running".equalsIgnoreCase(c.status())) {
                    // TCP health check to determine READY or FAILED
                    var healthConfig = HealthCheckConfig.forImage(c.image());
                    String ip = c.ip();
                    if (ip != null && !ip.isEmpty() && tcpCheck(ip, healthConfig.port())) {
                        state = ContainerLifecycleState.READY;
                    } else {
                        state = ContainerLifecycleState.FAILED;
                        failureReason = "Health check failed on restore";
                    }
                } else if ("Stopped".equalsIgnoreCase(c.status())) {
                    state = ContainerLifecycleState.STOPPED;
                } else {
                    // CREATING, STOPPING, etc. — treat as FAILED (stale)
                    state = ContainerLifecycleState.FAILED;
                    failureReason = "Stale state on restore: " + c.status();
                }

                ContainerRecord record;
                if (state == ContainerLifecycleState.FAILED) {
                    // Build a CREATING record then transition to FAILED with reason
                    record = new ContainerRecord(c.name(), c.image(),
                            c.remote(), ContainerLifecycleState.CREATING)
                            .withFailed(failureReason);
                } else if (state == ContainerLifecycleState.READY) {
                    record = new ContainerRecord(c.name(), c.image(),
                            c.remote(), ContainerLifecycleState.CREATING)
                            .withState(ContainerLifecycleState.STARTING)
                            .withState(ContainerLifecycleState.READY)
                            .withIp(c.ip());
                } else {
                    // STOPPED: CREATING -> STARTING -> READY -> STOPPING -> STOPPED
                    record = new ContainerRecord(c.name(), c.image(),
                            c.remote(), ContainerLifecycleState.CREATING)
                            .withState(ContainerLifecycleState.STARTING)
                            .withState(ContainerLifecycleState.READY)
                            .withState(ContainerLifecycleState.STOPPING)
                            .withState(ContainerLifecycleState.STOPPED);
                }

                supervisor.tell(s -> s.registerContainerRecord(record));
                restored++;
                LOG.info("Restored container: " + c.name() + " -> " + state);
            }

            LOG.info("State restoration complete: " + restored + " containers restored");
        } catch (Exception e) {
            LOG.warning("State restoration failed: " + e.getMessage());
        }
    }

    /**
     * Single TCP connection attempt with 3-second timeout.
     */
    private boolean tcpCheck(String host, int port) {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 3000);
            return true;
        } catch (Exception e) {
            return false;
        }
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
     * Get the process supervisor actor ref.
     */
    public ActorRef<ProcessSupervisorActor> getProcessSupervisor() {
        return processSupervisor;
    }

    /**
     * Get the status actor ref.
     */
    public ActorRef<StatusActor> getStatusActor() {
        return statusActor;
    }
}
