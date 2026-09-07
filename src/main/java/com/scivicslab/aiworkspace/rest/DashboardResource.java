package com.scivicslab.aiworkspace.rest;

import com.scivicslab.aiworkspace.spi.ServiceBackend;
import com.scivicslab.aiworkspace.model.DashboardModel;
import com.scivicslab.aiworkspace.model.SessionState;
import com.scivicslab.aiworkspace.model.SessionView;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The three screens of the control dashboard ({@code ControlDashboard_260905_oo01}).
 *
 * <ul>
 *   <li>{@code /} — Catalog: what can be launched. The first screen, because launching something
 *       is what someone opens this portal to do.</li>
 *   <li>{@code /instances} — Instances: what is running, as a table</li>
 *   <li>{@code /instances/{tool}/{port}} — one instance: its settings and its log</li>
 *   <li>{@code /settings} — what this portal hands to every tool it launches</li>
 * </ul>
 *
 * <p>They were one page. The two questions "what can I launch" and "what is running" are asked at
 * different times and were stacked in one scroll, and the third — "why did this fail" — had no
 * answer at all.</p>
 */
@Path("/")
public class DashboardResource {

    @Inject
    Template dashboard;

    @Inject
    Template settings;

    @Inject
    ServiceBackend backend;

    @Inject
    ActivityProbe activityProbe;

    @Inject
    com.scivicslab.aiworkspace.web.AssetVersion assetVersionBean;

    /** How many lines of an instance's log the detail screen shows. */
    private static final int DETAIL_LOG_LINES = 200;

    // The running app's Maven version (e.g. 2.5.0-SNAPSHOT), shown in the header so the operator can
    // tell which build a Pod is running. Provided by Quarkus from the build's project version.
    @ConfigProperty(name = "quarkus.application.version", defaultValue = "dev")
    String appVersion;

    // The container image tag, baked into the image at build time (Dockerfile ARG IMAGE_TAG ->
    // ENV AI_WORKSPACE_IMAGE_TAG). Lets the header show the EXACT build (e.g. 2.5.0-2607041128),
    // which the Maven version alone cannot distinguish between two SNAPSHOT builds. Empty when unset.
    @ConfigProperty(name = "ai-workspace.image-tag")
    Optional<String> imageTag;

    @Inject
    com.scivicslab.aiworkspace.version.InstalledVersionReader installedVersionReader;

    @Inject
    com.scivicslab.aiworkspace.actor.AiWorkspaceActorSystem actors;

    /**
     * One row of the Instances table: a {@link SessionView} plus the uptime the screen shows, which
     * is a difference between two instants and so cannot be a field on the view itself.
     *
     * @param toolName  the tool
     * @param port      the port it listens on
     * @param memo      the name its launcher gave it, or the empty string
     * @param state     {@code READY}, {@code STARTING}, {@code FAILED} or {@code STOPPED}
     * @param accessUrl where to open it, or {@code null}
     * @param startedAt when it started, or {@code null}
     * @param uptime    how long it has been up, e.g. {@code "2h 13m"}, or {@code null}
     * @param params    the launch parameters
     * @param activity  what the instance says it is doing, or {@code ""}
     * @param activityAsOf when it said that, or {@code ""}
     */
    public record InstanceRow(String toolName, int port, String memo, String state,
                              String accessUrl, String startedAt, String uptime,
                              java.util.Map<String, String> params,
                              String activity, String activityAsOf) {}

    /**
     * One tile of the Catalog screen: a {@link com.scivicslab.aiworkspace.model.ToolView} plus the
     * three versions the tile shows, which are read per screen rather than held on the view.
     *
     * @param name           the tool
     * @param displayName    what the tile is headed with
     * @param icon           where the tool serves its own icon, or {@code null}
     * @param params         the launch parameters
     * @param github         owner and name of its repository, or {@code null}
     * @param status         the status line under the name
     * @param library        true when the tool has no jar file of its own to install
     * @param installed      the version the symbolic link in the works directory points at, or ""
     * @param latestRelease  the version of the newest release's tag, or ""
     * @param latestSnapshot the version the default branch's pom.xml declares, or ""
     */
    public record CatalogTile(String name, String displayName,
                              java.util.List<com.scivicslab.aiworkspace.model.ParamDefinition> params,
                              String github, String status, boolean library,
                              String installed, String latestRelease, String latestSnapshot) {}

    /**
     * The one screen: what is running, then what can be started, with each instance's own detail
     * folded under its row.
     *
     * <p>These were three screens for two days. Starting a tool and watching it come up were on
     * two of them, so one piece of work crossed a screen boundary, and whether a tool was already
     * running could not be seen while deciding to start it
     * ({@code SingleScreenAgain_260907_oo01}).</p>
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance dashboard() {
        List<InstanceRow> rows = withActivity(rows());
        return dashboard
            .data("screen", "dashboard")
            .data("version", appVersion)
            .data("assetVersion", assetVersionBean.value())
            .data("imageTag", imageTag.orElse(""))
            .data("instances", rows)
            .data("running", count(rows, SessionState.READY))
            .data("starting", count(rows, SessionState.STARTING))
            .data("failed", count(rows, SessionState.FAILED))
            .data("stopped", count(rows, SessionState.STOPPED))
            .data("launchTools", catalogTiles())
            .data("versionsFetchedAt", fetchedAtText())
            .data("versionsFailed", String.join(", ",
                    actors.toolVersions().ask(a -> a.failedTools()).join()));
    }

    /**
     * Where the Instances screen used to be.
     *
     * <p>Kept rather than removed: a browser tab left open on it, and anything that wrote the
     * address down, would otherwise get a 404.</p>
     */
    @GET
    @Path("/instances")
    public Response instancesMoved() {
        return Response.seeOther(java.net.URI.create("/")).build();
    }

    /**
     * Where one instance's own screen used to be. The detail is now folded under that instance's
     * row, and the name and port say which row to open.
     */
    @GET
    @Path("/instances/{tool}/{port}")
    public Response instanceMoved(@PathParam("tool") String tool, @PathParam("port") int port) {
        return Response.seeOther(java.net.URI.create("/?detail=" + tool + "-" + port)).build();
    }

    /**
     * Builds one tile per launchable tool, with the three versions filled in.
     *
     * <p>The installed version is read from the works directory every time this screen is drawn,
     * because reading a symbolic link costs nothing. The other two come from what was held by the
     * last {@code Refresh versions}, because reading them costs a request to GitHub
     * ({@code ToolVersions_260907_oo01}).
     */
    private List<CatalogTile> catalogTiles() {
        java.util.Map<String, com.scivicslab.aiworkspace.config.ToolRegistryEntry> registry =
            new java.util.LinkedHashMap<>();
        for (var e : com.scivicslab.aiworkspace.config.ToolRegistryLoader.load()) {
            registry.put(e.name(), e);
        }

        List<CatalogTile> tiles = new ArrayList<>();
        for (var tool : backend.getDashboardModel().launchTools()) {
            var entry = registry.get(tool.name());
            boolean library = entry != null && entry.library();
            String installed = (entry == null || library)
                ? ""
                : installedVersionReader.read(entry.jarFileName());
            var remote = actors.toolVersions().ask(a -> a.get(tool.name())).join();
            tiles.add(new CatalogTile(tool.name(), tool.displayName(), tool.params(),
                                      tool.github(), tool.status(), library,
                                      installed, remote.latestRelease(), remote.latestSnapshot()));
        }
        return tiles;
    }

    /** When the versions were last fetched, for the line beside the button, or "" if never. */
    private String fetchedAtText() {
        var at = actors.toolVersions().ask(a -> a.fetchedAt()).join();
        if (at == null) return "";
        return java.time.LocalDateTime.ofInstant(at, java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }



    /**
     * Settings — the values this portal resolved and hands to each tool it launches.
     *
     * <p>Read-only, and read the same way {@code ProcessSupervisor} reads them at launch, so what
     * is shown here is what a tool started now would receive ({@code ServiceDirectory_260905_oo01}).</p>
     */
    @GET
    @Path("/settings")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance settings() {
        String broker = System.getProperty("gpu.broker.url");
        if (broker == null || broker.isBlank()) broker = System.getenv("GPU_BROKER_URL");
        String portalPort = String.valueOf(com.scivicslab.aiworkspace.config.PortalPort.number());
        int start;
        try { start = Integer.parseInt(portalPort); } catch (NumberFormatException e) { start = -1; }
        return settings
            .data("screen", "settings")
            .data("version", appVersion)
            .data("assetVersion", assetVersionBean.value())
            .data("imageTag", imageTag.orElse(""))
            .data("gpuBrokerUrl", broker == null || broker.isBlank() ? "" : broker.replaceAll("/+$", ""))
            .data("aiWorkspaceUrl", "http://localhost:" + portalPort)
            .data("portalPort", portalPort)
            .data("portRange", start < 0 ? "—"
                    : "reserved " + (start + 1) + "-" + (start + 9) + ", pool " + (start + 10) + "-" + (start + 50))
            .data("workingDir", System.getProperty("user.dir", "—"));
    }

    /**
     * Fills in what each running instance says it is doing.
     *
     * <p>Only {@code READY} instances are asked: there is nothing on the other end of a port that
     * is still opening or already closed. All are asked at once, and one that does not answer
     * leaves its cell blank ({@code ActivitySummary_260905_oo01}).</p>
     */
    private List<InstanceRow> withActivity(List<InstanceRow> rows) {
        java.util.Map<String, String> ask = new java.util.LinkedHashMap<>();
        for (InstanceRow r : rows) {
            if (SessionState.READY.name().equals(r.state()) && r.accessUrl() != null) {
                ask.put(r.toolName() + ":" + r.port(), r.accessUrl());
            }
        }
        if (ask.isEmpty()) return rows;

        java.util.Map<String, ActivityProbe.Activity> answers = activityProbe.askAll(ask);
        List<InstanceRow> out = new ArrayList<>(rows.size());
        for (InstanceRow r : rows) {
            ActivityProbe.Activity a = answers.get(r.toolName() + ":" + r.port());
            out.add(a == null ? r
                    : new InstanceRow(r.toolName(), r.port(), r.memo(), r.state(), r.accessUrl(),
                                      r.startedAt(), r.uptime(), r.params(), a.summary(), a.asOf()));
        }
        return out;
    }

    /** Every instance the backend knows about, management services and launched tools alike. */
    private List<InstanceRow> rows() {
        DashboardModel model = backend.getDashboardModel();
        List<SessionView> all = new ArrayList<>();
        all.addAll(model.managementServices());
        all.addAll(model.activeSessions());

        List<InstanceRow> rows = new ArrayList<>();
        for (SessionView s : all) {
            rows.add(new InstanceRow(
                    s.toolName(), s.port(), s.memo() == null ? "" : s.memo(),
                    String.valueOf(s.state()), s.accessUrl(),
                    s.startedAt(), uptimeOf(s.startedAt()),
                    s.params() == null ? java.util.Map.of() : s.params(),
                    "", ""));
        }
        // By port. The backend hands these over grouped by tool, in the order the tools are defined
        // and then the order instances were launched, which is neither of the two orders someone
        // reading the table has in mind. The port is the one column that is unique per row and that
        // a reader already knows a value of when looking for a specific instance.
        rows.sort(java.util.Comparator.comparingInt(InstanceRow::port));
        return rows;
    }

    private static long count(List<InstanceRow> rows, SessionState state) {
        return rows.stream().filter(r -> state.name().equals(r.state())).count();
    }

    /**
     * How long ago {@code startedAt} was, as {@code 3d 4h}, {@code 2h 13m} or {@code 45s}.
     *
     * @param startedAt an ISO-8601 instant, or {@code null}
     * @return the elapsed time, or {@code null} when the start is unknown or unreadable
     */
    static String uptimeOf(String startedAt) {
        if (startedAt == null || startedAt.isBlank()) return null;
        Duration up;
        try {
            up = Duration.between(Instant.parse(startedAt), Instant.now());
        } catch (Exception e) {
            return null;
        }
        if (up.isNegative()) return null;
        long days = up.toDays();
        long hours = up.toHours() % 24;
        long minutes = up.toMinutes() % 60;
        if (days > 0) return days + "d " + hours + "h";
        if (up.toHours() > 0) return hours + "h " + minutes + "m";
        if (minutes > 0) return minutes + "m";
        return up.toSeconds() + "s";
    }
}
