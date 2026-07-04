package com.scivicslab.aiworkspace.rest;

import com.scivicslab.aiworkspace.spi.ServiceBackend;
import com.scivicslab.aiworkspace.model.DashboardModel;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import org.eclipse.microprofile.config.inject.ConfigProperty;

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

    // The running app's Maven version (e.g. 2.5.0-SNAPSHOT), shown in the header so the operator can
    // tell which build a Pod is running. Provided by Quarkus from the build's project version.
    @ConfigProperty(name = "quarkus.application.version", defaultValue = "dev")
    String appVersion;

    // The container image tag, baked into the image at build time (Dockerfile ARG IMAGE_TAG ->
    // ENV SERVICE_PORTAL_IMAGE_TAG). Lets the header show the EXACT build (e.g. 2.5.0-2607041128),
    // which the Maven version alone cannot distinguish between two SNAPSHOT builds. Empty when unset.
    @ConfigProperty(name = "service-portal.image-tag", defaultValue = "")
    String imageTag;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance get() {
        DashboardModel model = backend.getDashboardModel();
        return dashboard
            .data("version", appVersion)
            .data("imageTag", imageTag)
            .data("managementServices", model.managementServices())
            .data("activeSessions", model.activeSessions())
            .data("launchTools", model.launchTools())
            .data("mcpGateway", model.mcpGateway());
    }
}
