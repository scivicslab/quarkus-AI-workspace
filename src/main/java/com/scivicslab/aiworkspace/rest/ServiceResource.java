package com.scivicslab.aiworkspace.rest;

import com.scivicslab.aiworkspace.model.DashboardModel;
import com.scivicslab.aiworkspace.spi.ServiceBackend;
import com.scivicslab.aiworkspace.spi.ServiceException;

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

    @Inject
    com.scivicslab.aiworkspace.build.SnapshotBuildService snapshotBuilder;

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
    // MCP Gateway mode switching
    // ---------------------------------------------------------------

    @POST
    @Path("/mcp-gateway/use-external")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response useExternalGateway(Map<String, String> payload) {
        String url = payload != null ? payload.get("url") : null;
        if (url == null || url.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("success", false, "error", "url is required")).build();
        }
        try {
            backend.useExternalGateway(url);
            return ok();
        } catch (ServiceException e) {
            return error(e);
        }
    }

    @POST
    @Path("/mcp-gateway/use-internal")
    public Response useInternalGateway() {
        try {
            backend.useInternalGateway();
            return ok();
        } catch (ServiceException e) {
            return error(e);
        }
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
        String github = backend.getGithubRepo(name).orElse(null);
        if (github == null)
            return Response.status(404).entity(Map.of("error", "No GitHub repo configured for " + name)).build();

        String jarName = backend.getJarFileName(name).orElse(null);
        if (jarName == null)
            return Response.status(404).entity(Map.of("error", "No jar name configured for " + name)).build();

        try {
            HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

            // Fetch latest release metadata from GitHub API
            String apiUrl = "https://api.github.com/repos/" + github + "/releases/latest";
            HttpRequest apiReq = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "quarkus-ai-workspace")
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

            java.nio.file.Path worksDir = java.nio.file.Path.of(System.getProperty("user.dir"));
            java.nio.file.Path versionedDest = worksDir.resolve(assetName);

            // Download to a temp file first to avoid corrupting the existing JAR on failure
            java.nio.file.Path tmpDest = worksDir.resolve(assetName + ".tmp");
            java.nio.file.Files.deleteIfExists(tmpDest);
            HttpRequest dlReq = HttpRequest.newBuilder()
                .uri(URI.create(downloadUrl))
                .header("User-Agent", "quarkus-ai-workspace")
                .build();
            HttpResponse<java.nio.file.Path> dlResp = client.send(dlReq, HttpResponse.BodyHandlers.ofFile(tmpDest));
            if (dlResp.statusCode() < 200 || dlResp.statusCode() >= 300) {
                java.nio.file.Files.deleteIfExists(tmpDest);
                return Response.status(502)
                    .entity(Map.of("error", "Download returned HTTP " + dlResp.statusCode())).build();
            }

            // Verify the downloaded JAR is not corrupt before replacing the live file
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(tmpDest.toFile())) {
                if (zf.size() == 0) throw new java.io.IOException("JAR is empty");
            } catch (Exception e) {
                java.nio.file.Files.deleteIfExists(tmpDest);
                return Response.status(502)
                    .entity(Map.of("error", "Downloaded JAR is corrupt: " + e.getMessage())).build();
            }

            // Atomic replace: rename temp file to final destination
            java.nio.file.Files.move(tmpDest, versionedDest,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);

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

    /**
     * Starts a background build of the named tool from its GitHub source and installs
     * the resulting uber-jar into {@code ~/works/}. Use this instead of "Download Latest"
     * when the tool is only available as an unreleased {@code -SNAPSHOT}.
     *
     * <p>Returns immediately with a {@code jobId}; poll {@code /build-status/{jobId}}
     * for progress, since the Maven build takes minutes.
     */
    @POST
    @Path("/tool/{name}/build-snapshot")
    public Response buildSnapshot(@PathParam("name") String name) {
        String github = backend.getGithubRepo(name).orElse(null);
        if (github == null)
            return Response.status(404).entity(Map.of("error", "No GitHub repo configured for " + name)).build();

        String jarName = backend.getJarFileName(name).orElse(null);
        if (jarName == null)
            return Response.status(404).entity(Map.of("error", "No jar name configured for " + name)).build();

        var job = snapshotBuilder.start(name, github, jarName);
        return Response.ok(Map.of("jobId", job.id(), "state", job.state().name())).build();
    }

    /** Reports the progress of a snapshot build started via {@code build-snapshot}. */
    @GET
    @Path("/tool/{name}/build-status/{jobId}")
    public Response buildStatus(@PathParam("name") String name, @PathParam("jobId") String jobId) {
        var jobOpt = snapshotBuilder.get(jobId);
        if (jobOpt.isEmpty())
            return Response.status(404).entity(Map.of("error", "Unknown build job " + jobId)).build();

        var job = jobOpt.get();
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("jobId", job.id());
        body.put("state", job.state().name());
        body.put("step", job.step());
        body.put("log", String.join("\n", job.tail(40)));
        if (job.resultFile() != null) body.put("file", job.resultFile());
        if (job.error() != null) body.put("error", job.error());
        return Response.ok(body).build();
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
        // Locate each browser_download_url ending in .jar, then find the nearest
        // preceding "name" field. This avoids [^}]* breaking on nested objects
        // (e.g. the "uploader" sub-object inside each asset entry).
        Pattern urlPat = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.jar)\"");
        Pattern namePat = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
        Matcher urlM = urlPat.matcher(json);
        while (urlM.find()) {
            String url = urlM.group(1);
            String preceding = json.substring(0, urlM.start());
            Matcher nameM = namePat.matcher(preceding);
            String assetName = null;
            while (nameM.find()) assetName = nameM.group(1);
            if (assetName != null
                    && assetName.endsWith(".jar")
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
