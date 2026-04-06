package com.scivicslab.lxdpups.rest;

import com.scivicslab.lxdpups.actor.LxdPupsActorSystem;
import com.scivicslab.lxdpups.config.PortalConfigLoader;
import com.scivicslab.lxdpups.service.ContainerPortalPoller;
import com.scivicslab.lxdpups.service.StatusPoller;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

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

        return dashboard
                .data("title", config.getTitle())
                .data("managementServices", status.managementServices())
                .data("containers", status.containers())
                .data("workerTemplate", config.getWorkerTemplate())
                .data("containerProgress", containerPortalPoller.getAllProgress())
                .data("containerIps", containerIps)
                .data("activeLaunches", activeLaunches)
                .data("hostTools", config.getHostTools());
    }

    @GET
    @Path("/lxc-manager")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance lxcManagerPage() {
        return lxcManager.instance();
    }

}
