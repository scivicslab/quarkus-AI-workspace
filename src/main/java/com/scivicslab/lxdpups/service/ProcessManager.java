package com.scivicslab.lxdpups.service;

import com.scivicslab.lxdpups.config.PortalConfig;
import com.scivicslab.lxdpups.model.HostService;
import com.scivicslab.lxdpups.model.ServiceStatus;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Manages services as direct OS processes (no systemd dependency).
 * Downloads binaries from GitHub Releases when needed, then launches via ProcessBuilder.
 */
@ApplicationScoped
public class ProcessManager {

    private static final Logger LOG = Logger.getLogger(ProcessManager.class.getName());

    private final ConcurrentHashMap<String, ManagedProcess> processes = new ConcurrentHashMap<>();

    record ManagedProcess(String name, Process process, List<String> command) {}

    /**
     * Start a management service: download binary if needed, then launch.
     */
    public boolean start(PortalConfig.ManagementService svc) {
        var name = svc.getName();
        var existing = processes.get(name);
        if (existing != null && existing.process().isAlive()) {
            LOG.info("Service already running: " + name);
            return true;
        }

        var binary = svc.getBinary();
        if (binary == null) {
            LOG.warning("No binary config for service: " + name);
            return false;
        }

        // Download binary if not present
        var resolvedPath = resolvePath(binary.getPath());
        if (!Files.exists(Path.of(resolvedPath))) {
            try {
                downloadBinary(binary);
            } catch (Exception e) {
                LOG.warning("Failed to download binary for " + name + ": " + e.getMessage());
                return false;
            }
        }

        // Build command and launch
        var command = buildCommand(svc);
        LOG.info("Starting service " + name + ": " + command);

        try {
            var pb = new ProcessBuilder(command);
            pb.environment().put("QUARKUS_HTTP_PORT", String.valueOf(svc.getPort()));
            pb.redirectErrorStream(true);
            pb.inheritIO();
            var process = pb.start();
            processes.put(name, new ManagedProcess(name, process, command));
            LOG.info("Started service " + name + " (pid " + process.pid() + ")");
            return true;
        } catch (Exception e) {
            LOG.warning("Failed to start " + name + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Stop a service by destroying its process.
     */
    public boolean stop(String name) {
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
     */
    private void downloadBinary(PortalConfig.ManagementService.Binary binary) throws Exception {
        var url = String.format("https://github.com/%s/releases/download/%s/%s",
                binary.getRepo(), binary.getVersion(), binary.getAsset());
        var resolvedPath = resolvePath(binary.getPath());

        // Ensure parent directory exists
        var parentDir = Path.of(resolvedPath).getParent();
        if (parentDir != null) {
            Files.createDirectories(parentDir);
        }

        LOG.info("Downloading " + url + " to " + resolvedPath);
        var pb = new ProcessBuilder("curl", "-fLo", resolvedPath, url);
        pb.inheritIO();
        var process = pb.start();
        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("Download timed out");
        }
        if (process.exitValue() != 0) {
            throw new RuntimeException("curl exited with code " + process.exitValue());
        }

        // Make executable if not a JAR
        if (binary.getRuntime() == null) {
            Path.of(resolvedPath).toFile().setExecutable(true);
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
