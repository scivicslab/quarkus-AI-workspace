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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
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
    private static final Path PID_FILE = Path.of(System.getProperty("user.home"),
        ".cache", "service-portal", "children.pid");

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

        adoptOrKillOrphans();

        for (var tool : config.jvm().tools()) {
            // computeIfAbsent preserves any supervisors already adopted from the PID file
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
     * On startup, reads the PID file written by the previous session.
     * If a recorded process is still alive, it is adopted back into the
     * instances map as a READY supervisor (no new process is spawned).
     * If it is dead, it is treated as a true orphan and ignored.
     * Any entry that cannot be matched to a configured tool is killed.
     */
    private void adoptOrKillOrphans() {
        if (!Files.exists(PID_FILE)) return;
        try {
            List<String> remaining = new ArrayList<>();
            for (String line : Files.readAllLines(PID_FILE)) {
                String[] p = line.trim().split(" ");
                if (p.length < 3) continue;
                String toolName = p[0];
                int port;
                long pid;
                try {
                    port = Integer.parseInt(p[1]);
                    pid  = Long.parseLong(p[2]);
                } catch (NumberFormatException ignored) { continue; }

                ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
                if (handle == null || !handle.isAlive()) {
                    logger.info("Orphan already dead: " + toolName + ":" + port + " (PID " + pid + ")");
                    continue;
                }

                // Check if this tool is still in the config
                boolean knownTool = config.jvm() != null && config.jvm().tools().stream()
                    .anyMatch(t -> t.name().equals(toolName));

                if (!knownTool) {
                    logger.info("Tool removed from config, leaving process alone: "
                        + toolName + ":" + port + " (PID " + pid + ")");
                    continue;
                }

                // Adopt: create a supervisor that wraps the existing process
                ServicePortalConfig.ToolDefinition def;
                try { def = findTool(toolName); } catch (Exception e) { continue; }

                // Verify the live process is actually the expected java -jar invocation.
                // OS PID reuse can cause an unrelated process (e.g. emacs) to hold the
                // same PID after the original tool was stopped manually.
                if (!isExpectedJarProcess(handle, def.jar())) {
                    logger.warning("PID " + pid + " is alive but does not match jar '"
                        + def.jar() + "' — skipping adoption of " + toolName + ":" + port);
                    continue;
                }

                ProcessSupervisor supervisor = ProcessSupervisor.adopt(def, port, pid);
                instances.computeIfAbsent(toolName, k -> new CopyOnWriteArrayList<>()).add(supervisor);
                remaining.add(line);
                logger.info("Adopted existing process: " + toolName + ":" + port + " (PID " + pid + ")");
            }

            if (remaining.isEmpty()) {
                Files.deleteIfExists(PID_FILE);
            } else {
                Files.writeString(PID_FILE, String.join("\n", remaining) + "\n");
            }
        } catch (Exception e) {
            logger.warning("Failed to process orphan PID file: " + e.getMessage());
        }
    }

    /**
     * Returns true if the process identified by handle was launched with the
     * expected jar (or native binary). Checks that the command is "java" and
     * that one of the arguments contains the jar filename, or that the command
     * itself contains the binary filename for native executables.
     * This guards against OS PID reuse: a new unrelated process may have been
     * assigned the same PID after the original tool exited.
     */
    private static boolean isExpectedJarProcess(ProcessHandle handle, String jarPath) {
        if (jarPath == null || jarPath.isBlank()) return false;
        String jarFileName = java.nio.file.Path.of(jarPath).getFileName().toString();
        ProcessHandle.Info info = handle.info();
        String command = info.command().orElse("");
        String[] args  = info.arguments().orElse(new String[0]);
        boolean isJar    = jarFileName.endsWith(".jar");
        if (isJar) {
            boolean commandIsJava = command.endsWith("java") || command.endsWith("java.exe");
            boolean argHasJar     = java.util.Arrays.stream(args)
                .anyMatch(a -> a.contains(jarFileName));
            return commandIsJava && argHasJar;
        } else {
            // Native binary: the command path itself should contain the binary name
            return command.contains(jarFileName);
        }
    }

    private void recordPid(String toolName, int port, long pid) {
        try {
            Files.createDirectories(PID_FILE.getParent());
            Files.writeString(PID_FILE, toolName + " " + port + " " + pid + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            logger.warning("Failed to write PID file: " + e.getMessage());
        }
    }

    private void removePid(int port) {
        try {
            if (!Files.exists(PID_FILE)) return;
            var lines = Files.readAllLines(PID_FILE).stream()
                .filter(l -> !l.matches("\\S+ " + port + " \\d+"))
                .toList();
            Files.writeString(PID_FILE, String.join("\n", lines) + (lines.isEmpty() ? "" : "\n"));
        } catch (Exception e) {
            logger.warning("Failed to update PID file: " + e.getMessage());
        }
    }

    @Override
    public void startService(String toolName, Map<String, String> params) throws ServiceException {
        ServicePortalConfig.ToolDefinition def = findTool(toolName);
        CopyOnWriteArrayList<ProcessSupervisor> list =
            instances.computeIfAbsent(toolName, k -> new CopyOnWriteArrayList<>());

        // Clean up stopped instances before calculating port
        list.removeIf(s -> s.getState() == SessionState.STOPPED);

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
        long pid = supervisor.getPid();
        if (pid > 0) recordPid(toolName, port, pid);
    }

    @Override
    public void stopService(String toolName, int port) throws ServiceException {
        List<ProcessSupervisor> list = instances.getOrDefault(toolName, new CopyOnWriteArrayList<>());
        ProcessSupervisor target = list.stream()
            .filter(s -> s.getPort() == port)
            .findFirst()
            .orElseThrow(() -> new ServiceException("No instance of " + toolName + " on port " + port));
        target.stop();
        removePid(port);
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
                    for (var s : active) managementServices.add(s.toSessionView((name, port) -> "http://" + accessHost + ":" + port + "/"));
                }
            } else {
                // Active sessions section: non-stopped instances
                for (var s : list) {
                    if (s.getState() != SessionState.STOPPED) {
                        activeSessions.add(s.toSessionView((name, port) -> "http://" + accessHost + ":" + port + "/"));
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
