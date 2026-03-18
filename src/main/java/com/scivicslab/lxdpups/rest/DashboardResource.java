package com.scivicslab.lxdpups.rest;

import com.scivicslab.lxdpups.config.PortalConfigLoader;
import com.scivicslab.lxdpups.service.StatusPoller;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Dashboard HTML page rendered with Qute.
 */
@Path("/")
public class DashboardResource {

    @Inject
    Template dashboard;

    @Inject
    StatusPoller statusPoller;

    @Inject
    PortalConfigLoader configLoader;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance index() {
        var status = statusPoller.getLatestStatus();
        var config = configLoader.getConfig();
        return dashboard
                .data("title", config.getTitle())
                .data("managementServices", status.managementServices())
                .data("containers", status.containers())
                .data("workerTemplate", config.getWorkerTemplate());
    }
}
