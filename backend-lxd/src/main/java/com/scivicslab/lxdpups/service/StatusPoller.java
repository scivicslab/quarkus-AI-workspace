package com.scivicslab.lxdpups.service;

import com.scivicslab.lxdpups.actor.LxdPupsActorSystem;
import com.scivicslab.lxdpups.config.PortalConfigLoader;
import com.scivicslab.lxdpups.model.ContainerInfo;
import com.scivicslab.lxdpups.model.PortalStatus;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.logging.Logger;

/**
 * Periodically polls all services and containers for status updates.
 * Status is stored in StatusActor instead of AtomicReference.
 */
@ApplicationScoped
public class StatusPoller {

    private static final Logger LOG = Logger.getLogger(StatusPoller.class.getName());

    @Inject
    LxdPupsActorSystem actorSystem;

    @Inject
    HostServiceManager hostServiceManager;

    @Inject
    ContainerManager containerManager;

    @Inject
    PortalConfigLoader configLoader;

    @Scheduled(every = "10s")
    void poll() {
        try {
            var mgmtServices = hostServiceManager.getAllStatuses();

            var containers = new ArrayList<ContainerInfo>();
            var remotes = configLoader.getConfig().getRemotes();
            if (remotes.isEmpty()) {
                containers.addAll(containerManager.list(null));
            } else {
                for (var remote : remotes) {
                    containers.addAll(containerManager.list(remote.getName()));
                }
            }

            var status = new PortalStatus(mgmtServices, containers);
            actorSystem.getStatusActor().tell(a -> a.setStatus(status));
        } catch (Exception e) {
            LOG.warning("Status poll failed: " + e.getMessage());
        }
    }

    public PortalStatus getLatestStatus() {
        return actorSystem.getStatusActor().ask(a -> a.getStatus()).join();
    }

    /**
     * Force an immediate refresh (called after actions).
     */
    public void refresh() {
        poll();
    }
}
