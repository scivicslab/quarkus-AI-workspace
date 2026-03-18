package com.scivicslab.lxdpups.rest;

import com.scivicslab.lxdpups.model.PortalStatus;
import com.scivicslab.lxdpups.model.ServiceProgress;
import com.scivicslab.lxdpups.service.ContainerManager;
import com.scivicslab.lxdpups.service.HostServiceManager;
import com.scivicslab.lxdpups.service.ProcessManager;
import com.scivicslab.lxdpups.service.StatusPoller;
import com.scivicslab.lxdpups.service.ToolInstanceManager;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestStreamElementType;

import java.util.List;
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

    @Inject
    ProcessManager processManager;

    @Inject
    ToolInstanceManager toolInstanceManager;

    // ── Status ──

    @GET
    @Path("/status")
    public PortalStatus status() {
        return statusPoller.getLatestStatus();
    }

    // ── Management services (direct process management) ──

    @POST
    @Path("/management/services/{name}/start")
    public Response startManagementService(@PathParam("name") String name) {
        boolean ok = hostServiceManager.startAsync(name);
        return ok ? Response.accepted(Map.of("status", "starting")).build()
                  : Response.serverError().entity(Map.of("error", "Unknown service: " + name)).build();
    }

    @GET
    @Path("/management/services/{name}/progress")
    public ServiceProgress getServiceProgress(@PathParam("name") String name) {
        return processManager.getProgress(name);
    }

    @GET
    @Path("/management/services/{name}/progress/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<ServiceProgress> streamProgress(@PathParam("name") String name) {
        var current = processManager.getProgress(name);
        return Multi.createFrom().emitter(emitter -> {
            // Send current state immediately
            emitter.emit(current);
            if (current.done()) {
                emitter.complete();
                return;
            }
            // Register for future updates
            java.util.function.Consumer<ServiceProgress> listener = progress -> {
                emitter.emit(progress);
                if (progress.done()) {
                    emitter.complete();
                }
            };
            processManager.addProgressListener(name, listener);
            emitter.onTermination(() -> processManager.removeProgressListener(name, listener));
        });
    }

    @POST
    @Path("/management/services/{name}/stop")
    public Response stopManagementService(@PathParam("name") String name) {
        boolean ok = hostServiceManager.stop(name);
        statusPoller.refresh();
        return ok ? Response.ok(Map.of("status", "stopped")).build()
                  : Response.serverError().entity(Map.of("error", "Failed to stop " + name)).build();
    }

    @POST
    @Path("/management/services/{name}/restart")
    public Response restartManagementService(@PathParam("name") String name) {
        boolean ok = hostServiceManager.restart(name);
        statusPoller.refresh();
        return ok ? Response.ok(Map.of("status", "restarted")).build()
                  : Response.serverError().entity(Map.of("error", "Failed to restart " + name)).build();
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

    // ── Tool instances (container mode) ──

    @POST
    @Path("/tools/{name}/launch")
    public Response launchTool(@PathParam("name") String name) {
        int port = toolInstanceManager.launchTool(name);
        if (port < 0) {
            return Response.serverError()
                    .entity(Map.of("error", "Failed to launch tool: " + name))
                    .build();
        }
        return Response.ok(Map.of("status", "launched", "tool", name, "port", port)).build();
    }

    @POST
    @Path("/tools/{name}/stop")
    public Response stopTool(@PathParam("name") String name,
                             @QueryParam("port") int port) {
        boolean ok = toolInstanceManager.stopTool(name, port);
        return ok ? Response.ok(Map.of("status", "stopped")).build()
                  : Response.serverError().entity(Map.of("error", "Failed to stop tool")).build();
    }

    @GET
    @Path("/tools/instances")
    public List<ToolInstanceManager.ToolInstance> getToolInstances() {
        return toolInstanceManager.getRunningInstances();
    }
}
