package com.scivicslab.lxdpups.service;

import com.scivicslab.lxdpups.config.PortalConfigLoader;
import com.scivicslab.lxdpups.model.ContainerInfo;
import com.scivicslab.lxdpups.model.HostService;
import com.scivicslab.lxdpups.model.PortalStatus;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Periodically polls all services and containers for status updates.
 */
@ApplicationScoped
public class StatusPoller {

    private static final Logger LOG = Logger.getLogger(StatusPoller.class.getName());

    @Inject
    HostServiceManager hostServiceManager;

    @Inject
    ContainerManager containerManager;

    @Inject
    PortalConfigLoader configLoader;

    private final AtomicReference<PortalStatus> latestStatus = new AtomicReference<>(
            new PortalStatus(List.of(), List.of()));

    @Scheduled(every = "10s")
    void poll() {
        try {
            var mgmtServices = hostServiceManager.getAllStatuses();

            // List containers from all configured remotes
            var containers = new ArrayList<ContainerInfo>();
            var remotes = configLoader.getConfig().getRemotes();
            if (remotes.isEmpty()) {
                containers.addAll(containerManager.list(null));
            } else {
                for (var remote : remotes) {
                    containers.addAll(containerManager.list(remote.getName()));
                }
            }

            latestStatus.set(new PortalStatus(mgmtServices, containers));
        } catch (Exception e) {
            LOG.warning("Status poll failed: " + e.getMessage());
        }
    }

    public PortalStatus getLatestStatus() {
        return latestStatus.get();
    }

    /**
     * Force an immediate refresh (called after actions).
     */
    public void refresh() {
        poll();
    }
}
