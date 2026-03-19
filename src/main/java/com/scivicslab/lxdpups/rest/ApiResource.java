package com.scivicslab.lxdpups.rest;

import com.scivicslab.lxdpups.actor.LxdPupsActorSystem;
import com.scivicslab.lxdpups.model.ContainerInfo;
import com.scivicslab.lxdpups.model.ImageInfo;
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
import java.util.logging.Logger;

/**
 * REST API for portal operations.
 * Container operations go through the actor system (ask/tell).
 */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class ApiResource {

    private static final Logger LOG = Logger.getLogger(ApiResource.class.getName());

    @Inject
    StatusPoller statusPoller;

    @Inject
    HostServiceManager hostServiceManager;

    @Inject
    ContainerManager containerManager;

    @Inject
    LxdPupsActorSystem actorSystem;

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
            emitter.emit(current);
            if (current.done()) {
                emitter.complete();
                return;
            }
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

    // ── LXC management (all containers + images, via actor ask) ──

    @GET
    @Path("/lxc/containers")
    public List<ContainerInfo> listAllContainers(
            @QueryParam("remote") @DefaultValue("local") String remote) {
        try {
            return actorSystem.getSupervisor()
                    .ask(s -> s.listAllContainers(remote)).get();
        } catch (Exception e) {
            LOG.warning("Failed to list containers: " + e.getMessage());
            return List.of();
        }
    }

    @GET
    @Path("/lxc/images")
    public List<ImageInfo> listImages() {
        try {
            return actorSystem.getSupervisor()
                    .ask(s -> s.listImages()).get();
        } catch (Exception e) {
            LOG.warning("Failed to list images: " + e.getMessage());
            return List.of();
        }
    }

    // ── Worker containers (via actor tell/ask) ──

    @POST
    @Path("/containers")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response launchContainer(Map<String, String> body) {
        var name = body.get("name");
        var template = body.getOrDefault("template", "lxd-pups/ai-tools");
        var remote = body.getOrDefault("remote", "local");
        if (name == null || name.isBlank()) {
            return Response.status(400).entity(Map.of("error", "name is required")).build();
        }

        // Ask supervisor to launch (returns error message or null)
        try {
            var error = actorSystem.getSupervisor()
                    .ask(s -> s.requestLaunch(
                            actorSystem.getSupervisor(), template, name, remote))
                    .get();
            if (error != null) {
                return Response.status(409).entity(Map.of("error", error)).build();
            }
        } catch (Exception e) {
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }

        return Response.accepted(Map.of("status", "launching", "name", name)).build();
    }

    @GET
    @Path("/containers/{name}/launch/progress")
    public ServiceProgress getContainerLaunchProgress(@PathParam("name") String name) {
        try {
            return actorSystem.getSupervisor()
                    .ask(s -> s.getLaunchProgress(name)).get();
        } catch (Exception e) {
            return ServiceProgress.idle(name);
        }
    }

    @GET
    @Path("/containers/{name}/launch/progress/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<ServiceProgress> streamContainerLaunchProgress(@PathParam("name") String name) {
        return Multi.createFrom().emitter(emitter -> {
            // Poll supervisor every second for progress updates
            Thread.ofVirtual().start(() -> {
                try {
                    while (true) {
                        var progress = actorSystem.getSupervisor()
                                .ask(s -> s.getLaunchProgress(name)).get();
                        emitter.emit(progress);
                        if (progress.done()) {
                            emitter.complete();
                            return;
                        }
                        Thread.sleep(1000);
                    }
                } catch (Exception e) {
                    emitter.complete();
                }
            });
        });
    }

    @GET
    @Path("/containers/launching")
    public List<ServiceProgress> getActiveLaunches() {
        try {
            return actorSystem.getSupervisor()
                    .ask(s -> s.getAllActiveLaunches()).get();
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Stop = Delete (per lifecycle spec). Stops and removes the container instance.
     * The template image is not affected.
     */
    @POST
    @Path("/containers/{name}/stop")
    public Response stopContainer(@PathParam("name") String name,
                                  @QueryParam("remote") @DefaultValue("local") String remote) {
        try {
            var ok = actorSystem.getSupervisor()
                    .ask(s -> s.stopAndDeleteContainer(name, remote)).get();
            statusPoller.refresh();
            return ok ? Response.ok(Map.of("status", "deleted")).build()
                      : Response.serverError().entity(Map.of("error", "Failed to stop/delete")).build();
        } catch (Exception e) {
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    @DELETE
    @Path("/containers/{name}")
    public Response deleteContainer(@PathParam("name") String name,
                                    @QueryParam("remote") @DefaultValue("local") String remote) {
        try {
            var ok = actorSystem.getSupervisor()
                    .ask(s -> s.stopAndDeleteContainer(name, remote)).get();
            statusPoller.refresh();
            return ok ? Response.ok(Map.of("status", "deleted")).build()
                      : Response.serverError().entity(Map.of("error", "Failed to delete")).build();
        } catch (Exception e) {
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
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
