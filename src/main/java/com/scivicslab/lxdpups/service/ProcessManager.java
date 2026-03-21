package com.scivicslab.lxdpups.service;

import com.scivicslab.lxdpups.config.PortalConfig;
import com.scivicslab.lxdpups.model.HostService;
import com.scivicslab.lxdpups.model.ServiceProgress;
import com.scivicslab.lxdpups.model.ServiceStatus;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Manages services as direct OS processes (no systemd dependency).
 * Downloads binaries from GitHub Releases when needed, then launches via ProcessBuilder.
 * Tracks real-time progress for dashboard display.
 */
@ApplicationScoped
public class ProcessManager {

    private static final Logger LOG = Logger.getLogger(ProcessManager.class.getName());

    private final ConcurrentHashMap<String, ManagedProcess> processes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ProgressTracker> progressTrackers = new ConcurrentHashMap<>();

    record ManagedProcess(String name, Process process, List<String> command) {}

    /**
     * Mutable progress tracker for a service start operation.
     */
    static class ProgressTracker {
        private final String name;
        private volatile String phase = "starting";
        private final CopyOnWriteArrayList<String> messages = new CopyOnWriteArrayList<>();
        private volatile boolean done = false;
        private volatile boolean success = false;
        // Index of the mutable download progress line (-1 = none)
        private volatile int downloadLineIndex = -1;
        // SSE listeners for real-time push
        private final CopyOnWriteArrayList<Consumer<ServiceProgress>> listeners = new CopyOnWriteArrayList<>();

        ProgressTracker(String name) { this.name = name; }

        void setPhase(String phase) { this.phase = phase; notifyListeners(); }
        void addMessage(String msg) { messages.add(msg); notifyListeners(); }
        void complete(boolean ok) { this.done = true; this.success = ok; notifyListeners(); }

        /**
         * Update the download progress line in-place (replaces last progress line).
         * curl progress-bar output is continuously updated on the same line.
         */
        void updateDownloadLine(String line) {
            if (downloadLineIndex < 0) {
                downloadLineIndex = messages.size();
                messages.add(line);
            } else {
                messages.set(downloadLineIndex, line);
            }
            notifyListeners();
        }

        void addListener(Consumer<ServiceProgress> listener) { listeners.add(listener); }
        void removeListener(Consumer<ServiceProgress> listener) { listeners.remove(listener); }

        private void notifyListeners() {
            var snapshot = toProgress();
            for (var listener : listeners) {
                try {
                    listener.accept(snapshot);
                } catch (Exception e) {
                    // ignore broken listeners
                }
            }
        }

        ServiceProgress toProgress() {
            return new ServiceProgress(name, phase, List.copyOf(messages), done, success);
        }
    }

    /**
     * Start a management service asynchronously.
     * Returns immediately; progress can be tracked via getProgress().
     */
    public void startAsync(PortalConfig.ManagementService svc) {
        var name = svc.getName();
        var existing = processes.get(name);
        if (existing != null && existing.process().isAlive()) {
            LOG.info("Service already running: " + name);
            return;
        }

        var tracker = new ProgressTracker(name);
        progressTrackers.put(name, tracker);

        Thread.ofVirtual().name("start-" + name).start(() -> {
            try {
                doStart(svc, tracker);
            } catch (Exception e) {
                tracker.addMessage("Error: " + e.getMessage());
                tracker.complete(false);
                LOG.warning("Failed to start " + name + ": " + e.getMessage());
            }
        });
    }

    private void doStart(PortalConfig.ManagementService svc, ProgressTracker tracker) {
        var name = svc.getName();
        var binary = svc.getBinary();
        if (binary == null) {
            tracker.addMessage("No binary configuration found.");
            tracker.complete(false);
            return;
        }

        // Download binary if not present (skip for runtime-only tools like Docusaurus
        // and pre-deployed JARs with no GitHub repo)
        var resolvedPath = resolvePath(binary.getPath());
        boolean hasPath = resolvedPath != null && !resolvedPath.isEmpty();
        boolean hasRepo = binary.getRepo() != null && !binary.getRepo().isEmpty();
        if (hasPath && !Files.exists(Path.of(resolvedPath)) && hasRepo) {
            tracker.setPhase("downloading");
            tracker.addMessage("Downloading from " + binary.getRepo() + " " + binary.getVersion() + " ...");
            try {
                downloadBinary(binary, tracker);
            } catch (Exception e) {
                tracker.addMessage("Download failed: " + e.getMessage());
                tracker.complete(false);
                return;
            }
            tracker.addMessage("Download complete.");
        } else if (hasPath && !Files.exists(Path.of(resolvedPath))) {
            tracker.addMessage("Binary not found at " + resolvedPath + " (no repo configured for download)");
            tracker.complete(false);
            return;
        } else if (hasPath) {
            tracker.addMessage("Binary already exists at " + resolvedPath);
        }

        // Build command and launch
        tracker.setPhase("launching");
        var command = buildCommand(svc);
        tracker.addMessage("Launching: " + String.join(" ", command));

        try {
            var pb = new ProcessBuilder(command);
            // Use work-dir if specified, otherwise default to user home
            var workDir = (binary.getWorkDir() != null && !binary.getWorkDir().isEmpty())
                    ? resolvePath(binary.getWorkDir())
                    : System.getProperty("user.home");
            pb.directory(new java.io.File(workDir));
            tracker.addMessage("Working directory: " + workDir);
            pb.environment().put("QUARKUS_HTTP_PORT", String.valueOf(svc.getPort()));
            pb.environment().put("QUARKUS_HTTP_HOST", "0.0.0.0");
            // Add NVM node/yarn/npx to PATH if available
            appendNvmToPath(pb);
            pb.redirectErrorStream(true);
            pb.inheritIO();
            var process = pb.start();
            processes.put(name, new ManagedProcess(name, process, command));

            // Wait briefly to check if process crashes immediately
            Thread.sleep(2000);

            if (process.isAlive()) {
                tracker.addMessage("Service started (pid " + process.pid() + ").");
                tracker.setPhase("running");
                tracker.complete(true);
                LOG.info("Started service " + name + " (pid " + process.pid() + ")");
            } else {
                int exitCode = process.exitValue();
                tracker.addMessage("Process exited immediately with code " + exitCode + ".");
                tracker.complete(false);
                processes.remove(name);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            tracker.addMessage("Interrupted while starting.");
            tracker.complete(false);
        } catch (Exception e) {
            tracker.addMessage("Failed to launch: " + e.getMessage());
            tracker.complete(false);
        }
    }

    /**
     * Synchronous start (kept for backward compatibility in tests).
     */
    public boolean start(PortalConfig.ManagementService svc) {
        var name = svc.getName();
        var existing = processes.get(name);
        if (existing != null && existing.process().isAlive()) {
            return true;
        }

        var binary = svc.getBinary();
        if (binary == null) return false;

        var resolvedPath = resolvePath(binary.getPath());
        boolean hasRepo = binary.getRepo() != null && !binary.getRepo().isEmpty();
        if (resolvedPath != null && !resolvedPath.isEmpty() && !Files.exists(Path.of(resolvedPath))) {
            if (hasRepo) {
                try {
                    downloadBinary(binary, null);
                } catch (Exception e) {
                    return false;
                }
            } else {
                LOG.warning("Binary not found at " + resolvedPath + " (no repo configured)");
                return false;
            }
        }

        var command = buildCommand(svc);
        try {
            var pb = new ProcessBuilder(command);
            var syncWorkDir = (binary.getWorkDir() != null && !binary.getWorkDir().isEmpty())
                    ? resolvePath(binary.getWorkDir())
                    : System.getProperty("user.home");
            pb.directory(new java.io.File(syncWorkDir));
            pb.environment().put("QUARKUS_HTTP_PORT", String.valueOf(svc.getPort()));
            pb.environment().put("QUARKUS_HTTP_HOST", "0.0.0.0");
            appendNvmToPath(pb);
            pb.redirectErrorStream(true);
            pb.inheritIO();
            var process = pb.start();
            processes.put(name, new ManagedProcess(name, process, command));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get progress for a service start operation.
     */
    public ServiceProgress getProgress(String name) {
        var tracker = progressTrackers.get(name);
        if (tracker == null) {
            return ServiceProgress.idle(name);
        }
        return tracker.toProgress();
    }

    /**
     * Add a listener for real-time progress events (used by SSE endpoint).
     */
    public void addProgressListener(String name, Consumer<ServiceProgress> listener) {
        var tracker = progressTrackers.get(name);
        if (tracker != null) {
            tracker.addListener(listener);
        }
    }

    /**
     * Remove a progress listener.
     */
    public void removeProgressListener(String name, Consumer<ServiceProgress> listener) {
        var tracker = progressTrackers.get(name);
        if (tracker != null) {
            tracker.removeListener(listener);
        }
    }

    /**
     * Check if a service is currently in a start operation.
     */
    public boolean isStarting(String name) {
        var tracker = progressTrackers.get(name);
        return tracker != null && !tracker.done;
    }

    /**
     * Stop a service by destroying its process.
     * If the process was not started by this Portal (orphan), kills by port.
     */
    public boolean stop(String name, int port) {
        progressTrackers.remove(name);
        var managed = processes.remove(name);
        if (managed != null && managed.process().isAlive()) {
            LOG.info("Stopping service: " + name);
            managed.process().destroy();
            try {
                boolean exited = managed.process().waitFor(5, TimeUnit.SECONDS);
                if (!exited) {
                    LOG.warning("Service " + name + " did not stop gracefully, forcing");
                    managed.process().destroyForcibly();
                    managed.process().waitFor(3, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                managed.process().destroyForcibly();
            }
            return true;
        }

        // Try to kill orphan process by port
        if (port > 0) {
            return killByPort(port, name);
        }
        LOG.info("Service not running: " + name);
        return true;
    }

    /**
     * Stop a service (backward-compatible overload without port).
     */
    public boolean stop(String name) {
        return stop(name, 0);
    }

    /**
     * Kill a process listening on the given port using fuser.
     */
    private boolean killByPort(int port, String name) {
        try {
            var pb = new ProcessBuilder("fuser", "-k", port + "/tcp");
            pb.redirectErrorStream(true);
            var process = pb.start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (finished && process.exitValue() == 0) {
                LOG.info("Killed orphan process on port " + port + " for service " + name);
                return true;
            }
        } catch (Exception e) {
            LOG.warning("Failed to kill by port " + port + ": " + e.getMessage());
        }
        return false;
    }

    /**
     * Check if a service process is alive.
     * Falls back to port check for processes started outside this Portal instance.
     */
    public ServiceStatus getStatus(String name, int port) {
        // Check if currently starting
        if (isStarting(name)) {
            return ServiceStatus.STARTING;
        }

        var managed = processes.get(name);
        if (managed != null) {
            if (managed.process().isAlive()) {
                return ServiceStatus.ACTIVE;
            }
            // Process exited - check exit code
            int exitCode = managed.process().exitValue();
            processes.remove(name);
            return exitCode == 0 ? ServiceStatus.INACTIVE : ServiceStatus.FAILED;
        }

        // No managed process — check if port is in use (orphan process from previous Portal run)
        if (port > 0 && isPortInUse(port)) {
            return ServiceStatus.ACTIVE;
        }
        return ServiceStatus.INACTIVE;
    }

    /**
     * Check if a TCP port is in use by attempting to connect.
     */
    private boolean isPortInUse(int port) {
        try (var socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("localhost", port), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get the process name listening on a given port using ss command.
     * Returns null if unable to determine.
     */
    public String getProcessNameOnPort(int port) {
        try {
            var pb = new ProcessBuilder("ss", "-tlnp", "sport", "=", ":" + port);
            pb.redirectErrorStream(true);
            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes()).trim();
            boolean finished = process.waitFor(3, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            // Parse ss output: look for users:(("process-name",pid=NNN,...))
            for (var line : output.split("\n")) {
                int usersIdx = line.indexOf("users:((");
                if (usersIdx >= 0) {
                    // Extract "process-name" from users:(("process-name",pid=NNN,...))
                    int start = line.indexOf("\"", usersIdx);
                    int end = line.indexOf("\"", start + 1);
                    if (start >= 0 && end > start) {
                        var procName = line.substring(start + 1, end);
                        // Also extract pid
                        int pidIdx = line.indexOf("pid=", end);
                        if (pidIdx >= 0) {
                            int pidEnd = line.indexOf(",", pidIdx);
                            if (pidEnd < 0) pidEnd = line.indexOf(")", pidIdx);
                            if (pidEnd > pidIdx) {
                                return procName + " (pid " + line.substring(pidIdx + 4, pidEnd) + ")";
                            }
                        }
                        return procName;
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    /**
     * Get statuses for all configured management services.
     */
    public List<HostService> getAllStatuses(List<PortalConfig.ManagementService> services) {
        var result = new ArrayList<HostService>();
        for (var svc : services) {
            if (!svc.isEnabled()) continue;
            var status = getStatus(svc.getName(), svc.getPort());
            String processName = null;
            if (status == ServiceStatus.ACTIVE) {
                var managed = processes.get(svc.getName());
                if (managed != null && managed.process().isAlive()) {
                    processName = String.join(" ", managed.command());
                } else {
                    // Orphan process — look up by port
                    processName = getProcessNameOnPort(svc.getPort());
                }
            }
            result.add(new HostService(
                    svc.getName(), svc.getUnit(), svc.getPort(),
                    svc.getDescription(), svc.getUi(), status, processName));
        }
        return result;
    }

    /**
     * Download binary from GitHub Release using curl.
     * Captures stderr progress output and feeds it to the ProgressTracker in real-time.
     */
    private void downloadBinary(PortalConfig.ManagementService.Binary binary, ProgressTracker tracker) throws Exception {
        var url = String.format("https://github.com/%s/releases/download/%s/%s",
                binary.getRepo(), binary.getVersion(), binary.getAsset());
        var resolvedPath = resolvePath(binary.getPath());

        // Ensure parent directory exists
        var parentDir = Path.of(resolvedPath).getParent();
        if (parentDir != null) {
            Files.createDirectories(parentDir);
        }

        LOG.info("Downloading " + url + " to " + resolvedPath);
        if (tracker != null) {
            tracker.addMessage("URL: " + url);
            tracker.addMessage("Destination: " + resolvedPath);
        }

        // Use --write-out to get download stats, capture stderr for progress
        var pb = new ProcessBuilder("curl", "-fL", "-o", resolvedPath, "--progress-bar", url);
        pb.redirectErrorStream(true);
        var process = pb.start();

        // Read stdout+stderr (curl progress goes to stderr, merged here)
        Thread.ofVirtual().name("curl-progress-" + binary.getAsset()).start(() -> {
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    var trimmed = line.trim();
                    if (!trimmed.isEmpty() && tracker != null) {
                        // curl progress-bar outputs % lines like "###  43.2%"
                        tracker.updateDownloadLine(trimmed);
                    }
                }
            } catch (Exception e) {
                // ignore read errors on process exit
            }
        });

        boolean finished = process.waitFor(300, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("Download timed out after 300s");
        }
        if (process.exitValue() != 0) {
            throw new RuntimeException("curl exited with code " + process.exitValue());
        }

        // Report file size
        var fileSize = Files.size(Path.of(resolvedPath));
        var sizeMB = String.format("%.1f MB", fileSize / (1024.0 * 1024.0));
        if (tracker != null) tracker.addMessage("Downloaded " + sizeMB);

        // Make executable if not a JAR
        if (binary.getRuntime() == null) {
            Path.of(resolvedPath).toFile().setExecutable(true);
            if (tracker != null) tracker.addMessage("Set executable permission.");
        }
    }

    /**
     * Build the launch command for a service.
     */
    List<String> buildCommand(PortalConfig.ManagementService svc) {
        var binary = svc.getBinary();
        var resolvedPath = resolvePath(binary.getPath());
        var command = new ArrayList<String>();

        if (binary.getRuntime() == null) {
            // Native binary
            command.add(resolvedPath);
        } else if ("java".equals(binary.getRuntime())) {
            // Java JAR — put -D flags before -jar so they are JVM system properties
            command.add("java");
            var postJarArgs = new ArrayList<String>();
            if (binary.getArgs() != null && !binary.getArgs().isBlank()) {
                for (var arg : binary.getArgs().split("\\s+")) {
                    if (arg.startsWith("-D") || arg.startsWith("-X") || arg.startsWith("-javaagent")) {
                        command.add(arg);
                    } else {
                        postJarArgs.add(arg);
                    }
                }
            }
            command.add("-jar");
            command.add(resolvedPath);
            command.addAll(postJarArgs);
            return command;
        } else {
            // Generic runtime (e.g. npx, yarn) — resolve full path via NVM if needed
            command.add(resolveRuntime(binary.getRuntime()));
            // Only include path if non-empty (runtime-only tools like Docusaurus have no path)
            if (resolvedPath != null && !resolvedPath.isEmpty()) {
                command.add(resolvedPath);
            }
        }

        // Append extra arguments if specified
        if (binary.getArgs() != null && !binary.getArgs().isBlank()) {
            for (var arg : binary.getArgs().split("\\s+")) {
                command.add(arg);
            }
        }

        return command;
    }

    /**
     * Resolve a runtime command (e.g. "yarn", "npx") to its full path.
     * Checks NVM bin directory first, then falls back to the bare command name.
     */
    static String resolveRuntime(String runtime) {
        if (runtime == null) return null;
        // Already an absolute path
        if (runtime.startsWith("/")) return runtime;
        // Check NVM
        var nvmBin = findNvmBinDir();
        if (nvmBin != null) {
            var candidate = nvmBin.resolve(runtime);
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        // Fallback: bare command (relies on system PATH)
        return runtime;
    }

    /**
     * Find the NVM bin directory for the latest installed Node version.
     */
    private static Path findNvmBinDir() {
        var home = System.getProperty("user.home");
        var nvmDir = Path.of(home, ".nvm", "versions", "node");
        if (!Files.isDirectory(nvmDir)) return null;
        try (var versions = Files.list(nvmDir)) {
            return versions.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted(java.util.Collections.reverseOrder())
                    .findFirst()
                    .map(v -> nvmDir.resolve(v).resolve("bin"))
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Append NVM node bin directory to PATH if ~/.nvm exists.
     * This allows ProcessBuilder to find node, yarn, npx without sourcing nvm.sh.
     */
    private void appendNvmToPath(ProcessBuilder pb) {
        var home = System.getProperty("user.home");
        var nvmDir = Path.of(home, ".nvm", "versions", "node");
        if (!Files.isDirectory(nvmDir)) return;
        try (var versions = Files.list(nvmDir)) {
            // Pick the latest version directory
            var latest = versions.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted(java.util.Collections.reverseOrder())
                    .findFirst();
            if (latest.isPresent()) {
                var binDir = nvmDir.resolve(latest.get()).resolve("bin").toString();
                var currentPath = pb.environment().getOrDefault("PATH", "/usr/bin:/bin");
                pb.environment().put("PATH", binDir + ":" + currentPath);
            }
        } catch (Exception e) {
            // ignore — NVM not available
        }
    }

    /**
     * Resolve ~ to user home directory.
     */
    static String resolvePath(String path) {
        if (path != null && path.startsWith("~/")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }

    /**
     * Stop all managed processes on application shutdown.
     */
    void onShutdown(@Observes ShutdownEvent event) {
        LOG.info("Shutting down all managed processes");
        for (var name : new ArrayList<>(processes.keySet())) {
            stop(name);
        }
    }
}
