package com.scivicslab.serviceportal.rest;

import com.scivicslab.serviceportal.model.ServiceStatus;
import com.scivicslab.serviceportal.model.DashboardModel;
import com.scivicslab.serviceportal.spi.ServiceBackend;
import com.scivicslab.serviceportal.spi.ServiceException;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * REST API for service management.
 */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class ServiceResource {

    @Inject
    ServiceBackend backend;

    @GET
    @Path("/status")
    public Response getStatus() {
        DashboardModel model = backend.getDashboardModel();
        return Response.ok(model).build();
    }

    // Management service endpoints
    @POST
    @Path("/mgmt/{name}/start")
    public Response startManagementService(@PathParam("name") String name) {
        try {
            backend.startService("mgmt:" + name);
            return Response.ok(Map.of("success", true)).build();
        } catch (ServiceException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(Map.of("success", false, "error", e.getMessage()))
                .build();
        }
    }

    @POST
    @Path("/mgmt/{name}/stop")
    public Response stopManagementService(@PathParam("name") String name) {
        try {
            backend.stopService("mgmt:" + name);
            return Response.ok(Map.of("success", true)).build();
        } catch (ServiceException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(Map.of("success", false, "error", e.getMessage()))
                .build();
        }
    }

    @GET
    @Path("/mgmt/{name}/progress")
    public Response getManagementServiceProgress(@PathParam("name") String name) {
        // TODO: Implement progress tracking
        return Response.ok(Map.of("phase", "COMPLETE", "messages", List.of())).build();
    }

    // Tool endpoints
    @POST
    @Path("/tool/{name}/launch")
    public Response launchTool(@PathParam("name") String name) {
        try {
            backend.startService("tool:" + name);
            return Response.ok(Map.of("success", true)).build();
        } catch (ServiceException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(Map.of("success", false, "error", e.getMessage()))
                .build();
        }
    }

    @POST
    @Path("/tool/{name}/{port}/stop")
    public Response stopTool(@PathParam("name") String name, @PathParam("port") int port) {
        try {
            backend.stopService("tool:" + name + ":" + port);
            return Response.ok(Map.of("success", true)).build();
        } catch (ServiceException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(Map.of("success", false, "error", e.getMessage()))
                .build();
        }
    }

    @POST
    @Path("/tool/{name}/{port}/memo")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateToolMemo(@PathParam("name") String name,
                                   @PathParam("port") int port,
                                   Map<String, String> payload) {
        String memo = payload.get("memo");
        // TODO: Implement memo persistence
        return Response.ok(Map.of("success", true)).build();
    }

    // Container endpoints
    @POST
    @Path("/container/{name}/start")
    public Response startContainer(@PathParam("name") String name) {
        try {
            backend.startService("container:" + name);
            return Response.ok(Map.of("success", true)).build();
        } catch (ServiceException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(Map.of("success", false, "error", e.getMessage()))
                .build();
        }
    }

    @POST
    @Path("/container/{name}/stop")
    public Response stopContainer(@PathParam("name") String name) {
        try {
            backend.stopService("container:" + name);
            return Response.ok(Map.of("success", true)).build();
        } catch (ServiceException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(Map.of("success", false, "error", e.getMessage()))
                .build();
        }
    }

    @POST
    @Path("/container/{name}/snapshot")
    public Response snapshotContainer(@PathParam("name") String name) {
        // TODO: Implement container snapshot
        return Response.ok(Map.of("success", true)).build();
    }

    // Legacy endpoints for backward compatibility
    @GET
    @Path("/services/status")
    public Response getServicesStatus() {
        List<ServiceStatus> statuses = backend.getServiceStatuses();
        return Response.ok(Map.of(
            "backend", backend.getBackendType(),
            "services", statuses
        )).build();
    }

    @POST
    @Path("/services/{id}/start")
    public Response startService(@PathParam("id") String serviceId) {
        try {
            backend.startService(serviceId);
            return Response.ok(Map.of("success", true)).build();
        } catch (ServiceException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(Map.of("success", false, "error", e.getMessage()))
                .build();
        }
    }

    @POST
    @Path("/services/{id}/stop")
    public Response stopService(@PathParam("id") String serviceId) {
        try {
            backend.stopService(serviceId);
            return Response.ok(Map.of("success", true)).build();
        } catch (ServiceException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(Map.of("success", false, "error", e.getMessage()))
                .build();
        }
    }

    @GET
    @Path("/services/{id}/logs")
    public Response getLogs(@PathParam("id") String serviceId,
                           @QueryParam("lines") @DefaultValue("100") int lines) {
        List<String> logs = backend.getServiceLogs(serviceId, lines);
        return Response.ok(Map.of("logs", logs)).build();
    }
}
