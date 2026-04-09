package com.scivicslab.serviceportal.rest;

import com.scivicslab.serviceportal.spi.ServiceBackend;
import com.scivicslab.serviceportal.model.DashboardModel;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Dashboard UI resource.
 */
@Path("/")
public class DashboardResource {

    @Inject
    Template dashboard;

    @Inject
    ServiceBackend backend;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance get() {
        DashboardModel model = backend.getDashboardModel();
        return dashboard
            .data("managementServices", model.managementServices())
            .data("activeSessions", model.activeSessions())
            .data("launchTools", model.launchTools());
    }
}
