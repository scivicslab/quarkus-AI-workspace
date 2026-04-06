package com.scivicslab.lxdpups.rest;

import com.scivicslab.lxdpups.actor.LxdPupsActorSystem;
import com.scivicslab.lxdpups.model.ContainerInfo;
import com.scivicslab.lxdpups.model.ContainerRecord;
import com.scivicslab.lxdpups.model.ImageInfo;
import com.scivicslab.lxdpups.model.PortalStatus;
import com.scivicslab.lxdpups.model.ServiceProgress;
import com.scivicslab.lxdpups.service.ContainerManager;
import com.scivicslab.lxdpups.service.HostServiceManager;
import com.scivicslab.lxdpups.service.ProcessManager;
import com.scivicslab.lxdpups.service.StatusPoller;
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
    com.scivicslab.lxdpups.config.PortalConfigLoader configLoader;

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
        return Multi.createFrom().emitter(emitter -> {
            Thread.ofVirtual().name("stream-svc-" + name).start(() -> {
                try {
                    while (true) {
                        var progress = processManager.getProgress(name);
                        emitter.emit(progress);
                        if (progress.done()) break;
                        Thread.sleep(1000);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    // ignore
                } finally {
                    emitter.complete();
                }
            });
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

    // ── Container lifecycle records ──

    @GET
    @Path("/containers/{name}/record")
    public Response getContainerRecord(@PathParam("name") String name) {
        try {
            ContainerRecord record = actorSystem.getSupervisor()
                    .ask(s -> s.getContainerRecord(name)).get();
            if (record == null) {
                return Response.status(404).entity(Map.of("error", "No record for " + name)).build();
            }
            return Response.ok(Map.of(
                    "name", record.getName(),
                    "image", record.getImage(),
                    "remote", record.getRemote(),
                    "state", record.getState().name(),
                    "ip", record.getIp() != null ? record.getIp() : "",
                    "createdAt", record.getCreatedAt().toString(),
                    "lastActivityAt", record.getLastActivityAt().toString(),
                    "failureReason", record.getFailureReason() != null ? record.getFailureReason() : ""
            )).build();
        } catch (Exception e) {
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    @GET
    @Path("/containers/records")
    public Response getAllContainerRecords() {
        try {
            var records = actorSystem.getSupervisor()
                    .ask(s -> s.getAllContainerRecords()).get();
            var list = records.values().stream().map(r -> Map.of(
                    "name", r.getName(),
                    "image", r.getImage(),
                    "remote", r.getRemote(),
                    "state", r.getState().name(),
                    "ip", r.getIp() != null ? r.getIp() : "",
                    "createdAt", r.getCreatedAt().toString(),
                    "lastActivityAt", r.getLastActivityAt().toString(),
                    "failureReason", r.getFailureReason() != null ? r.getFailureReason() : ""
            )).toList();
            return Response.ok(list).build();
        } catch (Exception e) {
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @Path("/containers/{name}/activity")
    public Response recordActivity(@PathParam("name") String name) {
        try {
            actorSystem.getSupervisor().tell(s -> s.recordActivity(name));
            return Response.ok(Map.of("status", "recorded")).build();
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

}
