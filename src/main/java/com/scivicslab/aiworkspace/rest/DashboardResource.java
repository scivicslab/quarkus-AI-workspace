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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The three screens of the control dashboard ({@code ControlDashboard_260905_oo01}).
 *
 * <ul>
 *   <li>{@code /} — Instances: what is running, as a table</li>
 *   <li>{@code /catalog} — Catalog: what can be launched</li>
 *   <li>{@code /instances/{tool}/{port}} — one instance: its settings and its log</li>
 * </ul>
 *
 * <p>They were one page. The two questions "what can I launch" and "what is running" are asked at
 * different times and were stacked in one scroll, and the third — "why did this fail" — had no
 * answer at all.</p>
 */
@Path("/")
public class DashboardResource {

    @Inject
    Template instances;

    @Inject
    Template catalog;

    @Inject
    Template instance;

    @Inject
    ServiceBackend backend;

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
     */
    public record InstanceRow(String toolName, int port, String memo, String state,
                              String accessUrl, String startedAt, String uptime,
                              java.util.Map<String, String> params) {}

    /** Instances — the screen opened most often, so it is the one at the root. */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance instances() {
        List<InstanceRow> rows = rows();
        return instances
            .data("screen", "instances")
            .data("version", appVersion)
            .data("imageTag", imageTag.orElse(""))
            .data("instances", rows)
            .data("running", count(rows, SessionState.READY))
            .data("starting", count(rows, SessionState.STARTING))
            .data("failed", count(rows, SessionState.FAILED))
            .data("stopped", count(rows, SessionState.STOPPED));
    }

    /** Catalog — what can be launched, with each tool's form hidden until Launch is pressed. */
    @GET
    @Path("/catalog")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance catalog() {
        return catalog
            .data("screen", "catalog")
            .data("version", appVersion)
            .data("imageTag", imageTag.orElse(""))
            .data("launchTools", backend.getDashboardModel().launchTools());
    }

    /**
     * One instance: what it was launched with, and its log.
     *
     * <p>The log is read through {@code getServiceLogs}, which falls back to the log file when the
     * instance is no longer in the instance list — which is the state it is in when someone comes
     * here to find out why it failed.</p>
     *
     * @param tool the tool name
     * @param port the port that instance ran on
     * @param lines how many log lines to show
     * @return the detail screen
     */
    @GET
    @Path("/instances/{tool}/{port}")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance instance(@PathParam("tool") String tool,
                                     @PathParam("port") int port,
                                     @QueryParam("lines") @DefaultValue("200") int lines) {
        int wanted = lines > 0 ? lines : DETAIL_LOG_LINES;
        InstanceRow row = rows().stream()
                .filter(r -> r.toolName().equals(tool) && r.port() == port)
                .findFirst()
                // Not in the list any more: it stopped, and its log file is the reason to be here.
                .orElse(new InstanceRow(tool, port, "", SessionState.STOPPED.name(),
                                        null, null, null, java.util.Map.of()));
        return instance
            .data("screen", "instances")
            .data("version", appVersion)
            .data("imageTag", imageTag.orElse(""))
            .data("instance", row)
            .data("logLines", wanted)
            .data("log", backend.getServiceLogs(tool, port, wanted));
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
                    s.params() == null ? java.util.Map.of() : s.params()));
        }
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
