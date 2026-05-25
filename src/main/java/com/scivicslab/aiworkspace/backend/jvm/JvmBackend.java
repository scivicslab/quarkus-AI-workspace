package com.scivicslab.aiworkspace.backend.jvm;

import com.scivicslab.aiworkspace.config.AiWorkspaceConfig;
import com.scivicslab.aiworkspace.model.DashboardModel;
import com.scivicslab.aiworkspace.model.McpGatewayStatus;
import com.scivicslab.aiworkspace.model.ParamDefinition;
import com.scivicslab.aiworkspace.model.SessionState;
import com.scivicslab.aiworkspace.model.SessionView;
import com.scivicslab.aiworkspace.model.ToolView;
import com.scivicslab.aiworkspace.spi.ServiceBackend;
import com.scivicslab.aiworkspace.spi.ServiceException;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * ServiceBackend implementation that manages java -jar child processes.
 *
 * <h2>Port allocation</h2>
 * <p><b>Port-range mode</b> (recommended for multi-team deployments):</p>
 * <pre>
 *   -Dservice.portal.port-range=28000-28019
 *       28000 → quarkus-AI-workspace dashboard
 *       28001 → first autoStart+fixedPort tool (mcp-gateway)
 *       28002-28019 → dynamic pool for on-demand tools
 * </pre>
 * <p><b>Legacy mode</b>: no port-range property; each tool uses its configured port
 * from the YAML with a 100-port scan window.</p>
 */
@RegisterForReflection
public class JvmBackend implements ServiceBackend {

    private static final Logger logger = Logger.getLogger(JvmBackend.class.getName());

    /** toolName -> list of instances (may have multiple per tool) */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<ProcessSupervisor>> instances
        = new ConcurrentHashMap<>();

    private AiWorkspaceConfig config;
    private String accessHost;

    /** -1 when port-range mode is inactive (legacy mode). */
    private int rangeStart = -1;
    private int rangeEnd   = -1;

    /**
     * External MCP Gateway URL, set via {@link #useExternalGateway(String)}.
     * When non-null, propagated to child processes as MCP_GATEWAY_URL instead of
     * the team-dedicated subprocess URL. Cleared by {@link #useInternalGateway()}.
     */
    private volatile String externalMcpGatewayUrl;

    @Override
    public void initialize(AiWorkspaceConfig config) {
        this.config = config;
        this.accessHost = config.accessHost() != null ? config.accessHost() : "localhost";

        String portRange = System.getProperty("service.portal.port-range", "").trim();
        if (!portRange.isBlank()) {
            try {
                String[] parts = portRange.split("-");
                rangeStart = Integer.parseInt(parts[0].trim());
                rangeEnd   = Integer.parseInt(parts[1].trim());
            } catch (Exception e) {
                logger.warning("Invalid service.portal.port-range '" + portRange + "' — deriving from http.port");
                rangeStart = -1;
            }
        }
        if (rangeStart < 0) {
            String httpPort = System.getProperty("quarkus.http.port", "28000").trim();
            try {
                rangeStart = Integer.parseInt(httpPort);
                rangeEnd   = rangeStart + 19;
            } catch (Exception e) {
                logger.warning("Could not parse quarkus.http.port '" + httpPort + "' — port-range mode inactive");
                rangeStart = -1;
            }
        }
        if (rangeStart >= 0) {
            logger.info("Port-range: " + rangeStart + "-" + rangeEnd
                + " (gateway=" + (rangeStart + 1) + ", pool=" + (rangeStart + 2) + "-" + rangeEnd + ")");
        }

        if (config.jvm() == null) return;

        scanAndAdoptByPort();

        for (var tool : config.jvm().tools()) {
            // computeIfAbsent preserves any supervisors already adopted by the port scan
            CopyOnWriteArrayList<ProcessSupervisor> existing =
                instances.computeIfAbsent(tool.name(), k -> new CopyOnWriteArrayList<>());
            if (tool.autoStart()) {
                boolean alreadyRunning = existing.stream()
                    .anyMatch(s -> s.getState() == SessionState.READY || s.getState() == SessionState.STARTING);
                if (alreadyRunning) {
                    logger.info("Skipping auto-start for " + tool.name() + " — adopted process already running");
                } else {
                    try {
                        startService(tool.name(), Map.of());
                    } catch (ServiceException e) {
                        logger.severe("Failed to auto-start " + tool.name() + ": " + e.getMessage());
                    }
                }
            }
        }
        logger.info("JVM backend initialized");
    }

    /**
     * On startup, scans all tool ports defined in ai-workspace-jvm.yaml.
     * For each port not already adopted, uses {@code ss -Htlnp} to find the PID
     * of the listening process, then verifies two conditions before adopting:
     * <ol>
     *   <li>The process is owned by the current OS user.</li>
     *   <li>The process arguments contain the resolved jar full path.</li>
     * </ol>
     * Processes that fail either condition are left untouched.
     * Uses an OS-specific command to map port → PID (ss on Linux, lsof on macOS,
     * netstat on Windows). If the command is unavailable or produces no output,
     * the scan for that port is silently skipped.
     */
    private void scanAndAdoptByPort() {
        if (config.jvm() == null) return;
        String currentUser = System.getProperty("user.name");

        if (rangeStart >= 0) {
            scanRangeAndAdopt(rangeStart + 1, rangeEnd, currentUser);
        } else {
            scanLegacyAndAdopt(currentUser);
        }
    }

    /**
     * Port-range mode: scan the entire assigned range and match each listening
     * port to a tool by JAR name.
     */
    private void scanRangeAndAdopt(int start, int end, String currentUser) {
        // Build resolvedJar → tool map
        java.util.Map<String, AiWorkspaceConfig.ToolDefinition> jarToTool = new java.util.LinkedHashMap<>();
        for (var tool : config.jvm().tools()) {
            String jar = ProcessSupervisor.resolveJarPath(ProcessSupervisor.expandEnvVars(tool.jar()));
            if (jar != null && !jar.isBlank()) jarToTool.put(jar, tool);
        }

        for (int port = start; port <= end; port++) {
            long pid = findPidByPort(port);
            if (pid < 0) continue;

            ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
            if (handle == null || !handle.isAlive()) continue;

            if (!currentUser.equals(handle.info().user().orElse(null))) {
                logger.info("Port " + port + " PID " + pid + " — different user, leaving alone");
                continue;
            }

            String[] args = handle.info().arguments().orElse(new String[0]);
            AiWorkspaceConfig.ToolDefinition matchedTool = null;
            for (var entry : jarToTool.entrySet()) {
                if (jarMatches(entry.getKey(), args)) { matchedTool = entry.getValue(); break; }
            }
            if (matchedTool == null) continue;

            CopyOnWriteArrayList<ProcessSupervisor> list =
                instances.computeIfAbsent(matchedTool.name(), k -> new CopyOnWriteArrayList<>());

            final int p = port;
            boolean alreadyAdopted = list.stream().anyMatch(s -> s.getPort() == p
                && (s.getState() == SessionState.READY || s.getState() == SessionState.STARTING));
            if (alreadyAdopted) continue;

            list.add(ProcessSupervisor.adopt(matchedTool, port, pid));
            logger.info("Range scan adopted: " + matchedTool.name() + ":" + port + " (PID " + pid + ")");
        }
    }

    /**
     * Legacy mode: per-tool 100-port window scan (backward compatibility).
     */
    private void scanLegacyAndAdopt(String currentUser) {
        for (var tool : config.jvm().tools()) {
            String resolvedJar = ProcessSupervisor.resolveJarPath(
                ProcessSupervisor.expandEnvVars(tool.jar()));
            if (resolvedJar == null || resolvedJar.isBlank()) continue;

            CopyOnWriteArrayList<ProcessSupervisor> list =
                instances.computeIfAbsent(tool.name(), k -> new CopyOnWriteArrayList<>());

            for (int port = tool.port(); port < tool.port() + 100; port++) {
                final int p = port;
                boolean alreadyAdopted = list.stream()
                    .anyMatch(s -> s.getPort() == p
                        && (s.getState() == SessionState.READY || s.getState() == SessionState.STARTING));
                if (alreadyAdopted) continue;

                long pid = findPidByPort(port);
                if (pid < 0) continue;

                ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
                if (handle == null || !handle.isAlive()) continue;

                if (!currentUser.equals(handle.info().user().orElse(null))) {
                    logger.info("Port " + port + " PID " + pid + " — different user, leaving alone");
                    continue;
                }

                String[] args = handle.info().arguments().orElse(new String[0]);
                if (!jarMatches(resolvedJar, args)) {
                    logger.info("Port " + port + " PID " + pid
                        + " does not match jar '" + new java.io.File(resolvedJar).getName() + "' — leaving alone");
                    continue;
                }

                list.add(ProcessSupervisor.adopt(tool, port, pid));
                logger.info("Port scan adopted: " + tool.name() + ":" + port + " (PID " + pid + ")");
            }
        }
    }

    /**
     * Returns the PID of the process listening on {@code port}, or -1 if none found.
     * Delegates to an OS-specific implementation.
     */
    private long findPidByPort(int port) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("linux"))  return findPidByPortLinux(port);
        if (os.contains("mac"))    return findPidByPortMac(port);
        if (os.contains("win"))    return findPidByPortWindows(port);
        return -1;
    }

    /**
     * Linux: {@code ss -Htlnp sport = :<port>}
     * Output contains {@code pid=<N>} in the users field.
     */
    private long findPidByPortLinux(int port) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "ss", "-Htlnp", "sport", "=", ":" + port);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output;
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(proc.getInputStream()))) {
                output = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
            }
            proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("pid=(\\d+)").matcher(output);
            if (m.find()) return Long.parseLong(m.group(1));
        } catch (Exception e) {
            logger.warning("Port scan failed (Linux) for port " + port + ": " + e.getMessage());
        }
        return -1;
    }

    /**
     * macOS: {@code lsof -nP -iTCP:<port> -sTCP:LISTEN}
     * Output header: COMMAND PID USER FD TYPE DEVICE SIZE NODE NAME
     * PID is in column index 1.
     */
    private long findPidByPortMac(int port) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "lsof", "-nP", "-iTCP:" + port, "-sTCP:LISTEN");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output;
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(proc.getInputStream()))) {
                output = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
            }
            proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            for (String line : output.split("\n")) {
                String[] parts = line.trim().split("\\s+");
                // Skip header row (PID column contains "PID")
                if (parts.length >= 2 && !parts[1].equals("PID")) {
                    try { return Long.parseLong(parts[1]); } catch (NumberFormatException ignored) {}
                }
            }
        } catch (Exception e) {
            logger.warning("Port scan failed (macOS) for port " + port + ": " + e.getMessage());
        }
        return -1;
    }

    /**
     * Windows: {@code netstat -ano} then match the LISTENING line for {@code :<port>}.
     * Last column is the PID.
     */
    private long findPidByPortWindows(int port) {
        try {
            ProcessBuilder pb = new ProcessBuilder("netstat", "-ano");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output;
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(proc.getInputStream()))) {
                output = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
            }
            proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            String portSuffix = ":" + port;
            for (String line : output.split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.toUpperCase().contains("LISTENING")) continue;
                String[] parts = trimmed.split("\\s+");
                // parts[1] = local address (e.g. 0.0.0.0:8080 or [::]:8080)
                if (parts.length >= 5 && parts[1].endsWith(portSuffix)) {
                    try { return Long.parseLong(parts[parts.length - 1]); } catch (NumberFormatException ignored) {}
                }
            }
        } catch (Exception e) {
            logger.warning("Port scan failed (Windows) for port " + port + ": " + e.getMessage());
        }
        return -1;
    }

    @Override
    public void startService(String toolName, Map<String, String> params) throws ServiceException {
        checkJavaAvailable();
        AiWorkspaceConfig.ToolDefinition def = findTool(toolName);
        CopyOnWriteArrayList<ProcessSupervisor> list =
            instances.computeIfAbsent(toolName, k -> new CopyOnWriteArrayList<>());

        // Clean up stopped/failed instances before calculating port
        list.removeIf(s -> s.getState() == SessionState.STOPPED
                        || s.getState() == SessionState.FAILED);

        if (def.singleInstance() && !list.isEmpty()) {
            throw new ServiceException(toolName + " is already running (singleInstance=true).");
        }

        // autoStart tools: stop any running instance before (re)starting
        if (def.autoStart() && !list.isEmpty()) {
            for (var s : List.copyOf(list)) s.stop();
            list.clear();
        }

        int port;
        if (rangeStart >= 0) {
            // Port-range mode
            if (def.fixedPort()) {
                port = rangeStart + 1;
                if (!isPortAvailable(port)) {
                    throw new ServiceException(toolName + " requires port " + port
                        + " (range start+1) but it is already in use.");
                }
            } else {
                port = findFreePortInRange(rangeStart + 2, rangeEnd);
            }
        } else {
            // Legacy mode
            if (def.fixedPort()) {
                port = def.port();
                if (!isPortAvailable(port)) {
                    throw new ServiceException(toolName + " requires fixed port :" + port
                        + " but the port is already in use. Free the port before starting " + toolName + ".");
                }
            } else {
                port = findFreePort(def.port(), list);
            }
        }
        ProcessSupervisor supervisor = new ProcessSupervisor(def, port, params);
        supervisor.setGatewayUrl(resolveGatewayUrl().orElse(null));
        list.add(supervisor);
        supervisor.start();
    }

    /**
     * Returns the MCP Gateway base URL to inject into newly-launched child processes.
     *
     * <ul>
     *   <li>External mode: returns the URL set via {@link #useExternalGateway(String)}.</li>
     *   <li>Internal mode: returns the team-dedicated subprocess URL only when that
     *       subprocess is in {@link SessionState#READY}. While starting / failed / stopped,
     *       returns {@link Optional#empty()} so the env var is not set on the child.</li>
     * </ul>
     */
    public Optional<String> resolveGatewayUrl() {
        if (externalMcpGatewayUrl != null && !externalMcpGatewayUrl.isBlank()) {
            return Optional.of(externalMcpGatewayUrl);
        }
        return findMcpGatewayTool().flatMap(tool -> {
            CopyOnWriteArrayList<ProcessSupervisor> list = instances.get(tool.name());
            if (list == null) return Optional.empty();
            return list.stream()
                .filter(s -> s.getState() == SessionState.READY)
                .findFirst()
                .map(s -> "http://localhost:" + s.getPort());
        });
    }

    /**
     * Locates the team-dedicated MCP Gateway tool definition. Convention:
     * the autoStart=true + fixedPort=true tool is the gateway (typically named "mcp-gateway").
     */
    private Optional<AiWorkspaceConfig.ToolDefinition> findMcpGatewayTool() {
        if (config == null || config.jvm() == null) return Optional.empty();
        return config.jvm().tools().stream()
            .filter(t -> t.autoStart() && t.fixedPort())
            .findFirst();
    }

    /**
     * Switch to External mode: stop the team-dedicated MCP Gateway subprocess (if running)
     * and adopt the given external URL as the MCP Gateway for newly-launched tools.
     *
     * @throws ServiceException if the URL is blank
     */
    public void useExternalGateway(String url) throws ServiceException {
        if (url == null || url.isBlank()) {
            throw new ServiceException("External MCP Gateway URL must not be blank");
        }
        String normalised = url.trim();
        // Stop team-dedicated subprocess (if any) before switching
        findMcpGatewayTool().ifPresent(tool -> {
            CopyOnWriteArrayList<ProcessSupervisor> list = instances.get(tool.name());
            if (list != null) {
                for (var s : List.copyOf(list)) {
                    if (s.getState() != SessionState.STOPPED && s.getState() != SessionState.FAILED) {
                        s.stop();
                    }
                }
                list.removeIf(s -> s.getState() == SessionState.STOPPED
                                || s.getState() == SessionState.FAILED);
            }
        });
        this.externalMcpGatewayUrl = normalised;
        logger.info("MCP Gateway switched to EXTERNAL mode: " + normalised);
    }

    /**
     * Switch back to Internal mode: clear the external URL and start the
     * team-dedicated MCP Gateway subprocess. No-op if already in Internal mode
     * with a running subprocess.
     */
    public void useInternalGateway() throws ServiceException {
        this.externalMcpGatewayUrl = null;
        var toolOpt = findMcpGatewayTool();
        if (toolOpt.isEmpty()) {
            logger.warning("useInternalGateway: no MCP Gateway tool definition found");
            return;
        }
        var tool = toolOpt.get();
        CopyOnWriteArrayList<ProcessSupervisor> list = instances.get(tool.name());
        boolean alreadyRunning = list != null && list.stream().anyMatch(
            s -> s.getState() == SessionState.READY || s.getState() == SessionState.STARTING);
        if (alreadyRunning) {
            logger.info("MCP Gateway switched to INTERNAL mode (subprocess already running)");
            return;
        }
        startService(tool.name(), Map.of());
        logger.info("MCP Gateway switched to INTERNAL mode (subprocess started)");
    }

    @Override
    public void stopService(String toolName, int port) throws ServiceException {
        List<ProcessSupervisor> list = instances.getOrDefault(toolName, new CopyOnWriteArrayList<>());
        ProcessSupervisor target = list.stream()
            .filter(s -> s.getPort() == port)
            .findFirst()
            .orElseThrow(() -> new ServiceException("No instance of " + toolName + " on port " + port));
        target.stop();
    }

    @Override
    public List<String> getServiceLogs(String toolName, int port, int lines) {
        return instances.getOrDefault(toolName, new CopyOnWriteArrayList<>()).stream()
            .filter(s -> s.getPort() == port)
            .findFirst()
            .map(s -> s.getRecentLogs(lines))
            .orElse(List.of());
    }

    @Override
    public DashboardModel getDashboardModel() {
        List<SessionView> managementServices = new ArrayList<>();
        List<SessionView> activeSessions = new ArrayList<>();
        List<ToolView> launchTools = new ArrayList<>();

        if (config == null || config.jvm() == null) {
            return new DashboardModel(managementServices, activeSessions, launchTools, buildMcpGatewayStatus());
        }

        boolean inExternal = externalMcpGatewayUrl != null && !externalMcpGatewayUrl.isBlank();

        for (var tool : config.jvm().tools()) {
            CopyOnWriteArrayList<ProcessSupervisor> list = instances.getOrDefault(tool.name(), new CopyOnWriteArrayList<>());

            if (tool.autoStart()) {
                // MCP Gateway (autoStart + fixedPort) tile is handled via Launch Tools + mcpGateway status,
                // so suppress it from Management Services entirely when in EXTERNAL mode.
                boolean isMcpGateway = tool.fixedPort();
                List<ProcessSupervisor> active = list.stream()
                    .filter(s -> s.getState() != SessionState.STOPPED && s.getState() != SessionState.FAILED)
                    .toList();
                if (isMcpGateway && inExternal) {
                    // In EXTERNAL mode the gateway subprocess is not running and the tile lives in Launch Tools
                } else if (active.isEmpty()) {
                    managementServices.add(stoppedView(tool));
                } else {
                    for (var s : active) managementServices.add(s.toSessionView((name, p) -> "http://localhost:" + p + "/"));
                }
                launchTools.add(toToolView(tool));
            } else if (tool.singleInstance()) {
                // Single-instance tool: always in launchTools; also in activeSessions when running
                List<ProcessSupervisor> active = list.stream()
                    .filter(s -> s.getState() != SessionState.STOPPED && s.getState() != SessionState.FAILED)
                    .toList();
                for (var s : active) activeSessions.add(s.toSessionView((name, p) -> "http://localhost:" + p + "/"));
                launchTools.add(toToolView(tool));
            } else {
                // Active sessions section: non-stopped instances
                for (var s : list) {
                    if (s.getState() != SessionState.STOPPED) {
                        activeSessions.add(s.toSessionView((name, p) -> "http://localhost:" + p + "/"));
                    }
                }
                // Launch tools section: always show tile
                launchTools.add(toToolView(tool));
            }
        }

        return new DashboardModel(managementServices, activeSessions, launchTools, buildMcpGatewayStatus());
    }

    /**
     * Builds the MCP Gateway status for the dashboard.
     * Reflects EXTERNAL mode if set, otherwise the team-dedicated subprocess state.
     */
    private McpGatewayStatus buildMcpGatewayStatus() {
        if (externalMcpGatewayUrl != null && !externalMcpGatewayUrl.isBlank()) {
            return new McpGatewayStatus("EXTERNAL", externalMcpGatewayUrl);
        }
        var toolOpt = findMcpGatewayTool();
        if (toolOpt.isEmpty()) {
            return new McpGatewayStatus("INTERNAL_STOPPED", null);
        }
        CopyOnWriteArrayList<ProcessSupervisor> list = instances.get(toolOpt.get().name());
        if (list == null || list.isEmpty()) {
            return new McpGatewayStatus("INTERNAL_STOPPED", null);
        }
        // Pick highest-priority state: READY > STARTING > FAILED > STOPPED
        SessionState best = SessionState.STOPPED;
        ProcessSupervisor pick = null;
        for (var s : list) {
            SessionState st = s.getState();
            if (st == SessionState.READY) { best = st; pick = s; break; }
            if (st == SessionState.STARTING && best != SessionState.READY) { best = st; pick = s; }
            else if (st == SessionState.FAILED && best != SessionState.READY && best != SessionState.STARTING) { best = st; pick = s; }
        }
        return switch (best) {
            case READY    -> new McpGatewayStatus("INTERNAL_READY",
                                pick != null ? "http://localhost:" + pick.getPort() : null);
            case STARTING -> new McpGatewayStatus("INTERNAL_STARTING", null);
            case FAILED   -> new McpGatewayStatus("INTERNAL_FAILED", null);
            default       -> new McpGatewayStatus("INTERNAL_STOPPED", null);
        };
    }

    @Override
    public void updateMemo(String toolName, int port, String memo) {
        instances.getOrDefault(toolName, new CopyOnWriteArrayList<>()).stream()
            .filter(s -> s.getPort() == port)
            .findFirst()
            .ifPresent(s -> s.setMemo(memo));
    }

    @Override
    public String getBackendType() {
        return "jvm";
    }

    // ---------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------

    /**
     * Port-range mode: finds the first free port in [start, end] across all tool instances.
     */
    private int findFreePortInRange(int start, int end) throws ServiceException {
        var usedPorts = instances.values().stream()
            .flatMap(List::stream)
            .filter(s -> s.getState() != SessionState.STOPPED && s.getState() != SessionState.FAILED)
            .map(ProcessSupervisor::getPort)
            .collect(java.util.stream.Collectors.toSet());

        for (int port = start; port <= end; port++) {
            if (usedPorts.contains(port)) continue;
            if (findPidByPort(port) < 0) return port;
        }
        throw new ServiceException("No free port in range " + start + "-" + end);
    }

    /**
     * Legacy mode: finds the first free TCP port starting from {@code basePort}.
     * Searches within the tool's own 100-port window.
     */
    private int findFreePort(int basePort, List<ProcessSupervisor> activeInstances) throws ServiceException {
        var usedPorts = activeInstances.stream()
            .map(ProcessSupervisor::getPort)
            .collect(java.util.stream.Collectors.toSet());

        for (int port = basePort; port < basePort + 100; port++) {
            if (usedPorts.contains(port)) continue;
            if (findPidByPort(port) < 0) return port;
        }
        throw new ServiceException("No free port found in range " + basePort + "-" + (basePort + 99));
    }

    private boolean isPortAvailable(int port) {
        return findPidByPort(port) < 0;
    }

    /**
     * Returns {@code true} if any element of {@code args} matches {@code resolvedJar}.
     * Matches on full path, bare filename, or path ending with {@code /filename}.
     * Package-private for testing.
     *
     * @param resolvedJar absolute or relative path of the expected jar
     * @param args        process argument array from {@link ProcessHandle.Info#arguments()}
     * @return {@code true} if a match is found
     */
    static boolean jarMatches(String resolvedJar, String[] args) {
        String basename = new java.io.File(resolvedJar).getName();
        return Arrays.stream(args).anyMatch(a ->
            a.contains(resolvedJar) || a.equals(basename) || a.endsWith("/" + basename));
    }

    /**
     * Verifies that a JRE/JDK {@code java} executable is reachable.
     * Checks {@code JAVA_HOME/bin/java} first, then falls back to PATH resolution.
     * Throws {@link ServiceException} with OS-specific installation instructions if not found.
     */
    private static void checkJavaAvailable() throws ServiceException {
        // 1. JAVA_HOME/bin/java
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            String exe = isWindows() ? "bin\\java.exe" : "bin/java";
            java.io.File javaBin = new java.io.File(javaHome, exe);
            if (javaBin.isFile() && javaBin.canExecute()) return;
        }
        // 2. PATH lookup via `java -version`
        try {
            Process p = new ProcessBuilder(isWindows() ? "java.exe" : "java", "-version")
                .redirectErrorStream(true)
                .start();
            p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            boolean exited = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (exited && p.exitValue() == 0) return;
        } catch (Exception ignored) {
            // java not found in PATH — fall through to error
        }
        throw new ServiceException(buildJavaNotFoundMessage());
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static String buildJavaNotFoundMessage() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return "Java (JRE/JDK) is not installed or not on PATH.\n"
                 + "\n"
                 + "Install options:\n"
                 + "  winget install Microsoft.OpenJDK.21\n"
                 + "  — or download from https://adoptium.net/\n"
                 + "\n"
                 + "After installing, restart AI Workspace.";
        } else if (os.contains("mac")) {
            return "Java (JRE/JDK) is not installed or not on PATH.\n"
                 + "\n"
                 + "Install options:\n"
                 + "  SDKman (recommended):\n"
                 + "    curl -s https://get.sdkman.io | bash\n"
                 + "    sdk install java 21-tem\n"
                 + "  Homebrew:\n"
                 + "    brew install openjdk@21\n"
                 + "\n"
                 + "After installing, restart AI Workspace.";
        } else {
            // Linux
            return "Java (JRE/JDK) is not installed or not on PATH.\n"
                 + "\n"
                 + "Install options:\n"
                 + "  SDKman (recommended):\n"
                 + "    curl -s https://get.sdkman.io | bash\n"
                 + "    sdk install java 21-tem\n"
                 + "  Debian/Ubuntu:\n"
                 + "    sudo apt install openjdk-21-jre\n"
                 + "  RHEL/Fedora:\n"
                 + "    sudo dnf install java-21-openjdk\n"
                 + "\n"
                 + "After installing, restart AI Workspace.";
        }
    }

    private AiWorkspaceConfig.ToolDefinition findTool(String name) throws ServiceException {
        if (config.jvm() == null) throw new ServiceException("No JVM config");
        return config.jvm().tools().stream()
            .filter(t -> t.name().equals(name))
            .findFirst()
            .orElseThrow(() -> new ServiceException("Tool not found: " + name));
    }

    private SessionView stoppedView(AiWorkspaceConfig.ToolDefinition tool) {
        int port = (rangeStart >= 0 && tool.fixedPort()) ? rangeStart + 1 : tool.port();
        return new SessionView(
            tool.name(), port, tool.name(), "",
            SessionState.STOPPED, null, Map.of(), "", List.of(), tool.github()
        );
    }

    private ToolView toToolView(AiWorkspaceConfig.ToolDefinition tool) {
        List<ParamDefinition> params = new ArrayList<>();
        if (tool.params() != null) {
            for (var p : tool.params()) {
                List<ParamDefinition.ParamOption> options = p.options() == null ? List.of()
                    : p.options().stream()
                        .map(o -> new ParamDefinition.ParamOption(o.value(), o.label()))
                        .toList();
                params.add(new ParamDefinition(
                    p.key(), p.label(), p.type(),
                    ProcessSupervisor.expandEnvVars(p.defaultVal()),
                    options
                ));
            }
        }
        return new ToolView(tool.name(), tool.name(), "", params, tool.github() != null ? tool.github() : "");
    }
}
