package com.scivicslab.serviceportal.backend.docker;

import com.scivicslab.serviceportal.config.ServicePortalConfig;
import com.scivicslab.serviceportal.model.DashboardModel;
import com.scivicslab.serviceportal.model.ParamDefinition;
import com.scivicslab.serviceportal.model.SessionState;
import com.scivicslab.serviceportal.model.SessionView;
import com.scivicslab.serviceportal.model.ToolView;
import com.scivicslab.serviceportal.spi.ServiceBackend;
import com.scivicslab.serviceportal.spi.ServiceException;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * ServiceBackend implementation for Docker/k8s environments.
 * Manages java -jar child processes inside the container.
 *
 * Multiple instances of the same tool can run simultaneously on different ports.
 * Port allocation: base port + number of existing active instances for that tool.
 */
@RegisterForReflection
public class DockerBackend implements ServiceBackend {

    private static final Logger logger = Logger.getLogger(DockerBackend.class.getName());

    /** toolName -> list of instances (may have multiple per tool) */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<ProcessSupervisor>> instances
        = new ConcurrentHashMap<>();

    private ServicePortalConfig config;
    private String accessHost;

    @Override
    public void initialize(ServicePortalConfig config) {
        this.config = config;
        this.accessHost = config.accessHost() != null ? config.accessHost() : "localhost";
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
        logger.info("Docker backend initialized");
    }

    /**
     * On startup, scans all tool ports defined in service-portal-jvm.yaml.
     * For each port not already adopted, uses {@code ss -Htlnp} to find the PID
     * of the listening process, then verifies two conditions before adopting:
     * <ol>
     *   <li>The process is owned by the current OS user.</li>
     *   <li>The process arguments contain the resolved jar full path.</li>
     * </ol>
     * Processes that fail either condition are left untouched.
     * This is a Linux-only operation; if {@code ss} is unavailable or returns no
     * output for a given port, the scan for that port is silently skipped.
     */
    private void scanAndAdoptByPort() {
        if (config.jvm() == null) return;
        String currentUser = System.getProperty("user.name");

        for (var tool : config.jvm().tools()) {
            String resolvedJar = ProcessSupervisor.resolveJarPath(
                ProcessSupervisor.expandEnvVars(tool.jar()));
            if (resolvedJar == null || resolvedJar.isBlank()) continue;

            CopyOnWriteArrayList<ProcessSupervisor> list =
                instances.computeIfAbsent(tool.name(), k -> new CopyOnWriteArrayList<>());

            // Scan basePort through basePort+99 — same range as findFreePort().
            // This recovers all instances including those on auto-allocated ports
            // (e.g. a second chat-ui on :28101 that was started in a previous session).
            for (int port = tool.port(); port < tool.port() + 100; port++) {
                // Skip if already adopted on this port
                final int p = port;
                boolean alreadyAdopted = list.stream()
                    .anyMatch(s -> s.getPort() == p
                        && (s.getState() == SessionState.READY || s.getState() == SessionState.STARTING));
                if (alreadyAdopted) continue;

                long pid = findPidByPort(port);
                if (pid < 0) continue;

                ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
                if (handle == null || !handle.isAlive()) continue;

                // Condition 1: owned by current OS user
                if (!currentUser.equals(handle.info().user().orElse(null))) {
                    logger.info("Port " + port + " PID " + pid
                        + " belongs to a different user — leaving alone");
                    continue;
                }

                // Condition 2: arguments contain the resolved jar full path
                String[] args = handle.info().arguments().orElse(new String[0]);
                boolean hasJar = Arrays.stream(args).anyMatch(a -> a.contains(resolvedJar));
                if (!hasJar) {
                    logger.info("Port " + port + " PID " + pid
                        + " does not match jar '" + resolvedJar + "' — leaving alone");
                    continue;
                }

                ProcessSupervisor supervisor = ProcessSupervisor.adopt(tool, port, pid);
                list.add(supervisor);
                logger.info("Port scan adopted: " + tool.name() + ":" + port
                    + " (PID " + pid + ")");
            }
        }
    }

    /**
     * Runs {@code ss -Htlnp sport = :<port>} and extracts the PID from the output.
     * Returns -1 if {@code ss} is unavailable, produces no output, or the output
     * cannot be parsed.
     */
    private long findPidByPort(int port) {
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
            if (m.find()) {
                return Long.parseLong(m.group(1));
            }
        } catch (Exception e) {
            logger.warning("Port scan failed for port " + port + ": " + e.getMessage());
        }
        return -1;
    }

    @Override
    public void startService(String toolName, Map<String, String> params) throws ServiceException {
        ServicePortalConfig.ToolDefinition def = findTool(toolName);
        CopyOnWriteArrayList<ProcessSupervisor> list =
            instances.computeIfAbsent(toolName, k -> new CopyOnWriteArrayList<>());

        // Clean up stopped/failed instances before calculating port
        list.removeIf(s -> s.getState() == SessionState.STOPPED
                        || s.getState() == SessionState.FAILED);

        int port;
        if (def.fixedPort()) {
            port = def.port();
            if (!isPortAvailable(port)) {
                throw new ServiceException(toolName + " requires fixed port :" + port
                    + " but the port is already in use. Free the port before starting " + toolName + ".");
            }
        } else {
            port = findFreePort(def.port(), list);
        }
        ProcessSupervisor supervisor = new ProcessSupervisor(def, port, params);
        list.add(supervisor);
        supervisor.start();
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

        if (config.jvm() == null) {
            return new DashboardModel(managementServices, activeSessions, launchTools);
        }

        for (var tool : config.jvm().tools()) {
            CopyOnWriteArrayList<ProcessSupervisor> list = instances.getOrDefault(tool.name(), new CopyOnWriteArrayList<>());

            if (tool.autoStart()) {
                // Management service section
                List<ProcessSupervisor> active = list.stream()
                    .filter(s -> s.getState() != SessionState.STOPPED)
                    .toList();
                if (active.isEmpty()) {
                    managementServices.add(stoppedView(tool));
                } else {
                    for (var s : active) managementServices.add(s.toSessionView((name, p) -> "http://" + accessHost + ":" + p + "/"));
                }
            } else {
                // Active sessions section: non-stopped instances
                for (var s : list) {
                    if (s.getState() != SessionState.STOPPED) {
                        activeSessions.add(s.toSessionView((name, p) -> "http://" + accessHost + ":" + p + "/"));
                    }
                }
                // Launch tools section: always show tile
                launchTools.add(toToolView(tool));
            }
        }

        return new DashboardModel(managementServices, activeSessions, launchTools);
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
     * Finds the first free TCP port starting from {@code basePort}.
     * Skips ports already claimed by active instances and ports that have
     * something else listening on them (occupied by another process).
     * Searches up to 100 ports before giving up.
     */
    private int findFreePort(int basePort, List<ProcessSupervisor> activeInstances) throws ServiceException {
        var usedPorts = activeInstances.stream()
            .map(ProcessSupervisor::getPort)
            .collect(java.util.stream.Collectors.toSet());

        for (int port = basePort; port < basePort + 100; port++) {
            if (usedPorts.contains(port)) continue;
            if (!isTcpPortOpen(port)) return port;
        }
        throw new ServiceException("No free port found in range " + basePort + "-" + (basePort + 99));
    }

    private boolean isTcpPortOpen(int port) {
        try (java.net.ServerSocket ss = new java.net.ServerSocket(port)) {
            return false; // successfully bound → port is free
        } catch (Exception e) {
            return true; // bind failed → port is in use
        }
    }

    private boolean isPortAvailable(int port) {
        return !isTcpPortOpen(port);
    }

    private ServicePortalConfig.ToolDefinition findTool(String name) throws ServiceException {
        if (config.jvm() == null) throw new ServiceException("No docker config");
        return config.jvm().tools().stream()
            .filter(t -> t.name().equals(name))
            .findFirst()
            .orElseThrow(() -> new ServiceException("Tool not found: " + name));
    }

    private SessionView stoppedView(ServicePortalConfig.ToolDefinition tool) {
        return new SessionView(
            tool.name(), tool.port(), tool.name(), "",
            SessionState.STOPPED, null, Map.of(), "", List.of()
        );
    }

    private ToolView toToolView(ServicePortalConfig.ToolDefinition tool) {
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
        return new ToolView(tool.name(), tool.name(), "", params);
    }
}
