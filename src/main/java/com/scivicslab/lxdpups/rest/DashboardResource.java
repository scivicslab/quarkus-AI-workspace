package com.scivicslab.lxdpups.rest;

import com.scivicslab.lxdpups.actor.LxdPupsActorSystem;
import com.scivicslab.lxdpups.config.PortalConfigLoader;
import com.scivicslab.lxdpups.service.ContainerPortalPoller;
import com.scivicslab.lxdpups.service.StatusPoller;
import com.scivicslab.lxdpups.service.ToolInstanceManager;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Dashboard HTML page rendered with Qute.
 */
@Path("/")
public class DashboardResource {

    private static final Logger LOG = Logger.getLogger(DashboardResource.class.getName());

    @Inject
    Template dashboard;

    @Inject
    @Location("lxc-manager.html")
    Template lxcManager;

    @Inject
    StatusPoller statusPoller;

    @Inject
    PortalConfigLoader configLoader;

    @Inject
    ContainerPortalPoller containerPortalPoller;

    @Inject
    ToolInstanceManager toolInstanceManager;

    @Inject
    LxdPupsActorSystem actorSystem;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance index() {
        var status = statusPoller.getLatestStatus();
        var config = configLoader.getConfig();

        // Build container name -> IP map for portal links
        var containerIps = new java.util.HashMap<String, String>();
        for (var c : status.containers()) {
            if (c.ip() != null && !c.ip().isEmpty()) {
                containerIps.put(c.name(), c.ip());
            }
        }

        // Get active launches from actor system
        var activeLaunches = java.util.List.<com.scivicslab.lxdpups.model.ServiceProgress>of();
        try {
            activeLaunches = actorSystem.getSupervisor()
                    .ask(s -> s.getAllActiveLaunches()).get();
        } catch (Exception e) {
            LOG.warning("Failed to get active launches: " + e.getMessage());
        }

        var instance = dashboard
                .data("title", config.getTitle())
                .data("hostMode", config.isHostMode())
                .data("containerMode", config.isContainerMode())
                .data("parentUrl", config.getParentUrl())
                .data("managementServices", status.managementServices())
                .data("containers", status.containers())
                .data("workerTemplate", config.getWorkerTemplate())
                .data("containerProgress", containerPortalPoller.getAllProgress())
                .data("containerIps", containerIps)
                .data("activeLaunches", activeLaunches);

        // Container mode specific data
        if (config.isContainerMode()) {
            instance = instance
                    .data("tools", config.getTools())
                    .data("toolInstances", toolInstanceManager.getRunningInstances())
                    .data("storageInfo", getStorageInfo())
                    .data("myIp", getMyIpAddress());
        } else {
            instance = instance
                    .data("tools", java.util.List.of())
                    .data("toolInstances", java.util.List.of())
                    .data("storageInfo", "")
                    .data("myIp", "localhost");
        }

        return instance;
    }

    @GET
    @Path("/lxc-manager")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance lxcManagerPage() {
        return lxcManager.instance();
    }

    /**
     * Get this container's IP address by checking eth0.
     * Falls back to hostname resolution.
     */
    String getMyIpAddress() {
        try {
            var interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                var iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                var addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    var addr = addrs.nextElement();
                    if (addr instanceof java.net.Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            LOG.fine("Could not get IP address: " + e.getMessage());
        }
        return "localhost";
    }

    /**
     * Get storage info for the works directory.
     * Parses df output to show available disk space.
     */
    String getStorageInfo() {
        try {
            var pb = new ProcessBuilder("df", "-h", System.getProperty("user.home"));
            pb.redirectErrorStream(true);
            var process = pb.start();
            boolean finished = process.waitFor(3, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "No storage mounted";
            }
            var reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            // Skip header line
            reader.readLine();
            var dataLine = reader.readLine();
            if (dataLine == null) return "No storage mounted";

            // Parse df output: Filesystem Size Used Avail Use% Mounted
            var parts = dataLine.trim().split("\\s+");
            if (parts.length >= 4) {
                return "~/works (" + parts[3] + " avail)";
            }
            return "No storage mounted";
        } catch (Exception e) {
            LOG.fine("Could not get storage info: " + e.getMessage());
            return "No storage mounted";
        }
    }
}
