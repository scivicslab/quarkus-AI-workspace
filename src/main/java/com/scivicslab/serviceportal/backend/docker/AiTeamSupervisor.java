package com.scivicslab.serviceportal.backend.docker;

import com.scivicslab.serviceportal.model.DashboardModel;
import com.scivicslab.serviceportal.model.SessionState;
import com.scivicslab.serviceportal.model.SessionView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * Manages the lifecycle of a single ai-toolkit Docker container.
 *
 * After start(), a background thread polls /api/status on the container's
 * inner portal port until it responds, then tracks the milestone.
 */
public class AiTeamSupervisor {

    private static final Logger logger = Logger.getLogger(AiTeamSupervisor.class.getName());
    private static final int POLL_INTERVAL_MS  = 5_000;
    private static final int START_TIMEOUT_MS  = 120_000; // 2 minutes
    private static final int CONTAINER_BASE    = 28080;   // ai-toolkit internal base port
    private static final int PORT_RANGE_SIZE   = 90;

    private final String teamName;
    private final int basePort;               // host-side base port (e.g. 28180)
    private final Map<String, String> params;
    private final String image;
    private final String vllmEndpoint;
    private final CopyOnWriteArrayList<String> startupLog = new CopyOnWriteArrayList<>();

    private volatile SessionState state    = SessionState.STARTING;
    private volatile boolean      stopping = false;
    private volatile String       milestone = "S0";

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build();

    public AiTeamSupervisor(String teamName, int basePort,
                            Map<String, String> params,
                            String image, String vllmEndpoint) {
        this.teamName     = teamName;
        this.basePort     = basePort;
        this.params       = Map.copyOf(params);
        this.image        = image;
        this.vllmEndpoint = vllmEndpoint;
    }

    // ---------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------

    public void start() {
        Thread.ofVirtual().start(this::runContainer);
    }

    public void stop() {
        stopping = true;
        try {
            run("sudo", "docker", "stop", containerName());
            run("sudo", "docker", "rm",   containerName());
        } catch (Exception e) {
            logger.warning("Error stopping container " + containerName() + ": " + e.getMessage());
        }
        state = SessionState.STOPPED;
        logger.info("AI team stopped: " + teamName);
    }

    // ---------------------------------------------------------------
    // Accessors
    // ---------------------------------------------------------------

    public String getTeamName()   { return teamName; }
    public int    getBasePort()   { return basePort; }
    public SessionState getState() { return state; }

    public SessionView toSessionView() {
        return new SessionView(
            "ai-toolkit",
            basePort,
            teamName,
            "",
            state,
            state == SessionState.READY ? "http://localhost:" + basePort : null,
            params,
            milestone,
            new ArrayList<>(startupLog)
        );
    }

    // ---------------------------------------------------------------
    // Private: container startup and polling
    // ---------------------------------------------------------------

    private void runContainer() {
        try {
            List<String> cmd = buildDockerRunCommand();
            logStartup("Starting AI team: " + teamName + " on port " + basePort);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            // Capture docker run output into startupLog
            Thread.ofVirtual().start(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) logStartup(line);
                } catch (Exception ignored) {}
            });

            int exit = proc.waitFor();
            if (exit != 0 && !stopping) {
                logStartup("docker run failed with exit code " + exit);
                state = SessionState.FAILED;
                return;
            }

            // Container launched — wait for inner portal to become reachable
            waitForInnerPortal();

        } catch (Exception e) {
            if (!stopping) {
                logger.severe("Failed to start AI team " + teamName + ": " + e.getMessage());
                state = SessionState.FAILED;
            }
        }
    }

    private void waitForInnerPortal() {
        // basePort on host maps to CONTAINER_BASE (28080) inside the container
        String statusUrl = "http://localhost:" + basePort + "/api/status";
        long deadline = System.currentTimeMillis() + START_TIMEOUT_MS;

        while (!stopping && System.currentTimeMillis() < deadline) {
            try {
                DashboardModel inner = fetchStatus(statusUrl);
                milestone = deriveMilestone(inner);
                if (state == SessionState.STARTING) {
                    state = SessionState.READY;
                    logger.info("AI team READY: " + teamName + " milestone=" + milestone);
                }
                // Continue polling to keep milestone current
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (Exception e) {
                // Not yet reachable — keep trying
                try { Thread.sleep(POLL_INTERVAL_MS); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); return;
                }
            }
        }

        if (!stopping && state == SessionState.STARTING) {
            logger.warning("AI team timed out waiting for inner portal: " + teamName);
            state = SessionState.FAILED;
        }
    }

    // ---------------------------------------------------------------
    // Private: docker command construction
    // ---------------------------------------------------------------

    private List<String> buildDockerRunCommand() {
        String uid  = String.valueOf(ProcessHandle.current().pid()); // fallback
        String home = System.getProperty("user.home", System.getenv("HOME"));
        String workdir = params.getOrDefault("workdir", home + "/works");

        // Resolve actual uid:gid
        String userArg = resolveUidGid();

        // Map host range [basePort, basePort+89] → container range [28080, 28169]
        int hostEnd      = basePort + PORT_RANGE_SIZE - 1;
        int containerEnd = CONTAINER_BASE + PORT_RANGE_SIZE - 1;

        List<String> cmd = new ArrayList<>(List.of(
            "sudo", "docker", "run", "-d",
            "--name", containerName(),
            "--user", userArg,
            "-p", basePort + "-" + hostEnd + ":" + CONTAINER_BASE + "-" + containerEnd,
            "-v", home + ":" + home,
            "-e", "HOME=" + home,
            "-e", "VLLM_ENDPOINT=" + ProcessSupervisor.expandEnvVars(vllmEndpoint),
            "-e", "SERVICE_PORTAL_BASE_PORT=" + basePort,
            "-w", workdir
        ));

        // Pass any extra params as env vars
        params.forEach((k, v) -> {
            if (!k.equals("workdir") && !k.equals("image")) {
                cmd.add("-e");
                cmd.add("TEAM_PARAM_" + k.toUpperCase() + "=" + v);
            }
        });

        cmd.add(image);
        return cmd;
    }

    private String resolveUidGid() {
        try {
            Process p = new ProcessBuilder("id", "-u").start();
            String uid = new String(p.getInputStream().readAllBytes()).trim();
            Process pg = new ProcessBuilder("id", "-g").start();
            String gid = new String(pg.getInputStream().readAllBytes()).trim();
            return uid + ":" + gid;
        } catch (Exception e) {
            return "1000:1000";
        }
    }

    private String containerName() {
        return "ai-toolkit-" + teamName;
    }

    // ---------------------------------------------------------------
    // Private: /api/status polling and milestone derivation
    // ---------------------------------------------------------------

    private DashboardModel fetchStatus(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(3))
            .GET()
            .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new Exception("HTTP " + resp.statusCode());
        return parseStatus(resp.body());
    }

    /**
     * Minimal JSON parsing for DashboardModel — avoids adding a JSON library dependency.
     * Checks whether tool names appear with state=READY in the response body.
     */
    private DashboardModel parseStatus(String json) {
        // We only need the milestone derivation, so parse into SessionView list lazily.
        // Look for "toolName":"<name>" near "state":"READY" patterns.
        List<SessionView> sessions = new ArrayList<>();
        String[] tools = { "quarkus-mcp-gateway", "quarkus-chat-ui", "turing-workflow-editor" };
        for (String tool : tools) {
            if (json.contains("\"" + tool + "\"") && json.contains("\"READY\"")) {
                // Crude but sufficient: if both the tool name and READY appear in the response
                // do a narrower check per tool section
                if (isToolReady(json, tool)) {
                    sessions.add(new SessionView(tool, 0, tool, "", SessionState.READY,
                        null, Map.of(), "", List.of()));
                }
            }
        }
        return new DashboardModel(sessions, List.of(), List.of());
    }

    /** Check if a specific tool appears with READY state in the JSON string. */
    private boolean isToolReady(String json, String toolName) {
        // Find all occurrences of toolName and check nearby text for READY
        int idx = 0;
        while ((idx = json.indexOf(toolName, idx)) != -1) {
            // Look at the surrounding ~200 chars for "READY"
            int from = Math.max(0, idx - 100);
            int to   = Math.min(json.length(), idx + toolName.length() + 100);
            if (json.substring(from, to).contains("\"READY\"")) return true;
            idx += toolName.length();
        }
        return false;
    }

    private String deriveMilestone(DashboardModel inner) {
        boolean gateway  = isReady(inner, "quarkus-mcp-gateway");
        boolean chat     = isReady(inner, "quarkus-chat-ui");
        boolean workflow = isReady(inner, "turing-workflow-editor");

        if (gateway && chat && workflow) return "S4-ready";
        if (gateway && chat)             return "S2-chat-ready";
        if (gateway)                     return "S1-gateway-ready";
        return "S0";
    }

    private boolean isReady(DashboardModel model, String toolName) {
        return model.managementServices().stream()
            .anyMatch(s -> s.toolName().equals(toolName) && s.state() == SessionState.READY);
    }

    // ---------------------------------------------------------------
    // Private: shell command helper
    // ---------------------------------------------------------------

    private void run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.waitFor();
    }

    private void logStartup(String line) {
        startupLog.add(line);
        while (startupLog.size() > 100) startupLog.remove(0);
    }
}
