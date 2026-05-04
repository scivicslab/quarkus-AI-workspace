package com.scivicslab.serviceportal.rest;

import com.scivicslab.serviceportal.config.ServicePortalConfig;
import com.scivicslab.serviceportal.config.ServicePortalConfigLoader;
import com.scivicslab.serviceportal.model.DashboardModel;
import com.scivicslab.serviceportal.spi.ServiceBackend;
import com.scivicslab.serviceportal.spi.ServiceException;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Logger logger = Logger.getLogger(ServiceResource.class.getName());

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
    // Download latest jar from GitHub Releases
    // ---------------------------------------------------------------

    /**
     * Downloads the latest release jar for the named tool from its GitHub repository.
     * Saves the versioned asset (e.g. {@code html-saurus-1.9.0.jar}) to {@code ~/works/},
     * then creates or replaces a symlink {@code ~/works/<jar>} pointing to the versioned file.
     */
    @POST
    @Path("/tool/{name}/download")
    public Response downloadLatest(@PathParam("name") String name) {
        ServicePortalConfig config = ServicePortalConfigLoader.load();
        if (config.jvm() == null)
            return Response.status(500).entity(Map.of("error", "No JVM config")).build();

        ServicePortalConfig.ToolDefinition tool = config.jvm().tools().stream()
            .filter(t -> t.name().equals(name))
            .findFirst().orElse(null);
        if (tool == null)
            return Response.status(404).entity(Map.of("error", "Tool not found: " + name)).build();

        String github = tool.github();
        if (github == null || github.isBlank())
            return Response.status(400).entity(Map.of("error", "No GitHub repo configured for " + name)).build();

        String jarName = tool.jar();
        if (jarName == null || jarName.isBlank())
            return Response.status(400).entity(Map.of("error", "No jar name configured for " + name)).build();

        try {
            HttpClient client = HttpClient.newHttpClient();

            // Fetch latest release metadata from GitHub API
            String apiUrl = "https://api.github.com/repos/" + github + "/releases/latest";
            HttpRequest apiReq = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "quarkus-service-portal")
                .build();
            HttpResponse<String> apiResp = client.send(apiReq, HttpResponse.BodyHandlers.ofString());
            if (apiResp.statusCode() != 200) {
                return Response.status(502)
                    .entity(Map.of("error", "GitHub API returned HTTP " + apiResp.statusCode())).build();
            }

            String releaseJson = apiResp.body();
            String version = extractJsonString(releaseJson, "tag_name");
            String[] asset = findJarAsset(releaseJson);  // [assetName, downloadUrl]
            if (asset == null) {
                return Response.status(404)
                    .entity(Map.of("error", "No suitable jar asset found in latest release of " + github)).build();
            }
            String assetName = asset[0];
            String downloadUrl = asset[1];

            java.nio.file.Path worksDir = java.nio.file.Path.of(System.getProperty("user.home"), "works");

            // Download to versioned filename, e.g. ~/works/html-saurus-1.9.0.jar
            java.nio.file.Path versionedDest = worksDir.resolve(assetName);
            HttpRequest dlReq = HttpRequest.newBuilder()
                .uri(URI.create(downloadUrl))
                .header("User-Agent", "quarkus-service-portal")
                .build();
            client.send(dlReq, HttpResponse.BodyHandlers.ofFile(versionedDest));

            // Replace symlink ~/works/<jarName> → <assetName> (relative, same directory)
            java.nio.file.Path symlink = worksDir.resolve(jarName);
            java.nio.file.Files.deleteIfExists(symlink);
            java.nio.file.Files.createSymbolicLink(symlink, java.nio.file.Path.of(assetName));

            logger.info("Downloaded " + name + " " + version + " → " + versionedDest + ", symlink " + symlink + " → " + assetName);
            return Response.ok(Map.of(
                "success", true,
                "version", version != null ? version : "unknown",
                "file", assetName,
                "symlink", jarName
            )).build();
        } catch (Exception e) {
            logger.warning("Download failed for " + name + ": " + e.getMessage());
            return Response.status(500).entity(Map.of("error", e.getMessage())).build();
        }
    }

    /** Extracts a top-level string value from a JSON object by key (simple regex, no full parser). */
    private static String extractJsonString(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Finds the first suitable jar asset in the GitHub releases JSON.
     * Skips javadoc and sources jars.
     *
     * @return [assetName, browser_download_url], or null if not found
     */
    private static String[] findJarAsset(String json) {
        Pattern assetPat = Pattern.compile(
            "\"name\"\\s*:\\s*\"([^\"]+)\"[^}]*?\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"",
            Pattern.DOTALL);
        Matcher m = assetPat.matcher(json);
        while (m.find()) {
            String assetName = m.group(1);
            String url = m.group(2);
            if (assetName.endsWith(".jar")
                    && !assetName.endsWith("-javadoc.jar")
                    && !assetName.endsWith("-sources.jar")) {
                return new String[]{assetName, url};
            }
        }
        return null;
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
