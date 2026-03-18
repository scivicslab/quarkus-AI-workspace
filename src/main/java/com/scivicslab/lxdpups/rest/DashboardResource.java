package com.scivicslab.lxdpups.rest;

import com.scivicslab.lxdpups.config.PortalConfigLoader;
import com.scivicslab.lxdpups.service.ContainerPortalPoller;
import com.scivicslab.lxdpups.service.StatusPoller;
import com.scivicslab.lxdpups.service.ToolInstanceManager;
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
    StatusPoller statusPoller;

    @Inject
    PortalConfigLoader configLoader;

    @Inject
    ContainerPortalPoller containerPortalPoller;

    @Inject
    ToolInstanceManager toolInstanceManager;

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

        var instance = dashboard
                .data("title", config.getTitle())
                .data("hostMode", config.isHostMode())
                .data("containerMode", config.isContainerMode())
                .data("managementServices", status.managementServices())
                .data("containers", status.containers())
                .data("workerTemplate", config.getWorkerTemplate())
                .data("containerProgress", containerPortalPoller.getAllProgress())
                .data("containerIps", containerIps);

        // Container mode specific data
        if (config.isContainerMode()) {
            instance = instance
                    .data("tools", config.getTools())
                    .data("toolInstances", toolInstanceManager.getRunningInstances())
                    .data("storageInfo", getStorageInfo());
        } else {
            instance = instance
                    .data("tools", java.util.List.of())
                    .data("toolInstances", java.util.List.of())
                    .data("storageInfo", "");
        }

        return instance;
    }

    /**
     * Get storage info for the works directory.
     * Parses df output to show available disk space.
     */
    String getStorageInfo() {
        try {
            var pb = new ProcessBuilder("df", "-h", "/home/ubuntu/works");
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
