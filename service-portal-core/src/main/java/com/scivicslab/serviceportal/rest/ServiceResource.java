package com.scivicslab.serviceportal.rest;

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
 *
 * Endpoints:
 *   GET  /api/status                        - DashboardModel (JSON)
 *   POST /api/mgmt/{name}/start             - Start management service
 *   POST /api/mgmt/{name}/stop              - Stop management service
 *   POST /api/tool/{name}/launch            - Launch new tool instance (body: params map)
 *   POST /api/tool/{name}/{port}/stop       - Stop tool instance
 *   POST /api/tool/{name}/{port}/memo       - Update memo for tool instance
 *   GET  /api/tool/{name}/{port}/logs       - Get recent logs
 */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class ServiceResource {

    @Inject
    ServiceBackend backend;

    @GET
    @Path("/status")
    public DashboardModel getStatus() {
        return backend.getDashboardModel();
    }

    // ---------------------------------------------------------------
    // Management services (autoStart=true tools)
    // ---------------------------------------------------------------

    @POST
    @Path("/mgmt/{name}/start")
    public Response startManagementService(@PathParam("name") String name) {
        try {
            backend.startService(name, Map.of());
            return ok();
        } catch (ServiceException e) {
            return error(e);
        }
    }

    @POST
    @Path("/mgmt/{name}/stop")
    public Response stopManagementService(@PathParam("name") String name,
                                          @QueryParam("port") int port) {
        try {
            backend.stopService(name, port);
            return ok();
        } catch (ServiceException e) {
            return error(e);
        }
    }

    // ---------------------------------------------------------------
    // Tool instances (autoStart=false tools, multiple instances)
    // ---------------------------------------------------------------

    @POST
    @Path("/tool/{name}/launch")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response launchTool(@PathParam("name") String name, Map<String, String> params) {
        try {
            backend.startService(name, params != null ? params : Map.of());
            return ok();
        } catch (ServiceException e) {
            return error(e);
        }
    }

    @POST
    @Path("/tool/{name}/{port}/stop")
    public Response stopTool(@PathParam("name") String name, @PathParam("port") int port) {
        try {
            backend.stopService(name, port);
            return ok();
        } catch (ServiceException e) {
            return error(e);
        }
    }

    @POST
    @Path("/tool/{name}/{port}/memo")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateMemo(@PathParam("name") String name,
                               @PathParam("port") int port,
                               Map<String, String> payload) {
        String memo = payload != null ? payload.getOrDefault("memo", "") : "";
        backend.updateMemo(name, port, memo);
        return ok();
    }

    @GET
    @Path("/tool/{name}/{port}/logs")
    public Response getLogs(@PathParam("name") String name,
                            @PathParam("port") int port,
                            @QueryParam("lines") @DefaultValue("50") int lines) {
        List<String> logs = backend.getServiceLogs(name, port, lines);
        return Response.ok(Map.of("logs", logs)).build();
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private Response ok() {
        return Response.ok(Map.of("success", true)).build();
    }

    private Response error(ServiceException e) {
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
            .entity(Map.of("success", false, "error", e.getMessage()))
            .build();
    }
}
