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

        ProgressTracker(String name) { this.name = name; }

        void setPhase(String phase) { this.phase = phase; }
        void addMessage(String msg) { messages.add(msg); }
        void complete(boolean ok) { this.done = true; this.success = ok; }

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

        // Download binary if not present
        var resolvedPath = resolvePath(binary.getPath());
        if (!Files.exists(Path.of(resolvedPath))) {
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
        } else {
            tracker.addMessage("Binary already exists at " + resolvedPath);
        }

        // Build command and launch
        tracker.setPhase("launching");
        var command = buildCommand(svc);
        tracker.addMessage("Launching: " + String.join(" ", command));

        try {
            var pb = new ProcessBuilder(command);
            pb.environment().put("QUARKUS_HTTP_PORT", String.valueOf(svc.getPort()));
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
        if (!Files.exists(Path.of(resolvedPath))) {
            try {
                downloadBinary(binary, null);
            } catch (Exception e) {
                return false;
            }
        }

        var command = buildCommand(svc);
        try {
            var pb = new ProcessBuilder(command);
            pb.environment().put("QUARKUS_HTTP_PORT", String.valueOf(svc.getPort()));
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
     * Check if a service is currently in a start operation.
     */
    public boolean isStarting(String name) {
        var tracker = progressTrackers.get(name);
        return tracker != null && !tracker.done;
    }

    /**
     * Stop a service by destroying its process.
     */
    public boolean stop(String name) {
        progressTrackers.remove(name);
        var managed = processes.remove(name);
        if (managed == null || !managed.process().isAlive()) {
            LOG.info("Service not running: " + name);
            return true;
        }

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

    /**
     * Check if a service process is alive.
     */
    public ServiceStatus getStatus(String name) {
        // Check if currently starting
        if (isStarting(name)) {
            return ServiceStatus.STARTING;
        }

        var managed = processes.get(name);
        if (managed == null) {
            return ServiceStatus.INACTIVE;
        }
        if (managed.process().isAlive()) {
            return ServiceStatus.ACTIVE;
        }
        // Process exited - check exit code
        int exitCode = managed.process().exitValue();
        processes.remove(name);
        return exitCode == 0 ? ServiceStatus.INACTIVE : ServiceStatus.FAILED;
    }

    /**
     * Get statuses for all configured management services.
     */
    public List<HostService> getAllStatuses(List<PortalConfig.ManagementService> services) {
        var result = new ArrayList<HostService>();
        for (var svc : services) {
            if (!svc.isEnabled()) continue;
            var status = getStatus(svc.getName());
            result.add(new HostService(
                    svc.getName(), svc.getUnit(), svc.getPort(),
                    svc.getDescription(), svc.getUi(), status));
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
            // Java JAR
            command.add("java");
            command.add("-jar");
            command.add(resolvedPath);
        } else {
            // Generic runtime
            command.add(binary.getRuntime());
            command.add(resolvedPath);
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
