package com.scivicslab.lxdpups.rest;

import com.scivicslab.lxdpups.model.PortalStatus;
import com.scivicslab.lxdpups.service.ContainerManager;
import com.scivicslab.lxdpups.service.HostServiceManager;
import com.scivicslab.lxdpups.service.StatusPoller;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * REST API for portal operations.
 */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class ApiResource {

    @Inject
    StatusPoller statusPoller;

    @Inject
    HostServiceManager hostServiceManager;

    @Inject
    ContainerManager containerManager;

    // ── Status ──

    @GET
    @Path("/status")
    public PortalStatus status() {
        return statusPoller.getLatestStatus();
    }

    // ── Management services (host systemctl) ──

    @POST
    @Path("/management/services/{unit}/start")
    public Response startManagementService(@PathParam("unit") String unit) {
        boolean ok = hostServiceManager.start(unit);
        statusPoller.refresh();
        return ok ? Response.ok(Map.of("status", "started")).build()
                  : Response.serverError().entity(Map.of("error", "Failed to start " + unit)).build();
    }

    @POST
    @Path("/management/services/{unit}/stop")
    public Response stopManagementService(@PathParam("unit") String unit) {
        boolean ok = hostServiceManager.stop(unit);
        statusPoller.refresh();
        return ok ? Response.ok(Map.of("status", "stopped")).build()
                  : Response.serverError().entity(Map.of("error", "Failed to stop " + unit)).build();
    }

    @POST
    @Path("/management/services/{unit}/restart")
    public Response restartManagementService(@PathParam("unit") String unit) {
        boolean ok = hostServiceManager.restart(unit);
        statusPoller.refresh();
        return ok ? Response.ok(Map.of("status", "restarted")).build()
                  : Response.serverError().entity(Map.of("error", "Failed to restart " + unit)).build();
    }

    // ── Worker containers (lxc commands) ──

    @POST
    @Path("/containers")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response launchContainer(Map<String, String> body) {
        var name = body.get("name");
        var template = body.getOrDefault("template", "lxd-pups/base");
        var remote = body.getOrDefault("remote", "local");
        if (name == null || name.isBlank()) {
            return Response.status(400).entity(Map.of("error", "name is required")).build();
        }
        boolean ok = containerManager.launch(template, name, remote);
        statusPoller.refresh();
        return ok ? Response.ok(Map.of("status", "launched", "name", name)).build()
                  : Response.serverError().entity(Map.of("error", "Failed to launch " + name)).build();
    }

    @POST
    @Path("/containers/{name}/start")
    public Response startContainer(@PathParam("name") String name,
                                   @QueryParam("remote") @DefaultValue("local") String remote) {
        boolean ok = containerManager.start(name, remote);
        statusPoller.refresh();
        return ok ? Response.ok(Map.of("status", "started")).build()
                  : Response.serverError().entity(Map.of("error", "Failed to start")).build();
    }

    @POST
    @Path("/containers/{name}/stop")
    public Response stopContainer(@PathParam("name") String name,
                                  @QueryParam("remote") @DefaultValue("local") String remote) {
        boolean ok = containerManager.stop(name, remote);
        statusPoller.refresh();
        return ok ? Response.ok(Map.of("status", "stopped")).build()
                  : Response.serverError().entity(Map.of("error", "Failed to stop")).build();
    }

    @DELETE
    @Path("/containers/{name}")
    public Response deleteContainer(@PathParam("name") String name,
                                    @QueryParam("remote") @DefaultValue("local") String remote) {
        boolean ok = containerManager.delete(name, remote);
        statusPoller.refresh();
        return ok ? Response.ok(Map.of("status", "deleted")).build()
                  : Response.serverError().entity(Map.of("error", "Failed to delete")).build();
    }

    @POST
    @Path("/containers/{name}/snapshot")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response snapshot(@PathParam("name") String name, Map<String, String> body) {
        var snapName = body.getOrDefault("snapshot", "snap-" + System.currentTimeMillis());
        var remote = body.getOrDefault("remote", "local");
        boolean ok = containerManager.snapshot(name, snapName, remote);
        return ok ? Response.ok(Map.of("status", "snapshot created", "snapshot", snapName)).build()
                  : Response.serverError().entity(Map.of("error", "Failed to snapshot")).build();
    }

    // ── Worker container services (lxc exec -- systemctl) ──

    @POST
    @Path("/containers/{name}/services/{unit}/start")
    public Response startContainerService(@PathParam("name") String name,
                                          @PathParam("unit") String unit,
                                          @QueryParam("remote") @DefaultValue("local") String remote) {
        boolean ok = containerManager.serviceStart(name, remote, unit);
        return ok ? Response.ok(Map.of("status", "started")).build()
                  : Response.serverError().entity(Map.of("error", "Failed to start service")).build();
    }

    @POST
    @Path("/containers/{name}/services/{unit}/stop")
    public Response stopContainerService(@PathParam("name") String name,
                                         @PathParam("unit") String unit,
                                         @QueryParam("remote") @DefaultValue("local") String remote) {
        boolean ok = containerManager.serviceStop(name, remote, unit);
        return ok ? Response.ok(Map.of("status", "stopped")).build()
                  : Response.serverError().entity(Map.of("error", "Failed to stop service")).build();
    }
}
