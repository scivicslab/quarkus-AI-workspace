package com.scivicslab.lxdpups.actor;

import com.scivicslab.lxdpups.config.PortalConfig;
import com.scivicslab.lxdpups.model.HostService;
import com.scivicslab.lxdpups.model.ServiceProgress;
import com.scivicslab.lxdpups.model.ServiceStatus;
import com.scivicslab.pojoactor.core.ActorRef;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Supervisor actor that owns all service process state.
 * Replaces ProcessManager's ConcurrentHashMap-based state.
 * Plain HashMaps are safe because the actor's single-threaded message loop
 * ensures sequential access.
 */
public class ProcessSupervisorActor {

    private static final Logger LOG = Logger.getLogger(ProcessSupervisorActor.class.getName());

    // State — plain fields, safe because actor is single-threaded
    private final HashMap<String, ActorRef<ProcessWorkerActor>> workers = new HashMap<>();
    private final HashMap<String, ProcessProgress> progressMap = new HashMap<>();
    private final HashMap<String, Process> runningProcesses = new HashMap<>();
    private final HashMap<String, List<String>> runningCommands = new HashMap<>();

    /**
     * Start a service asynchronously.
     * Checks if already running/starting, then creates a child worker actor.
     */
    public void startAsync(ActorRef<ProcessSupervisorActor> self, PortalConfig.ManagementService svc) {
        var name = svc.getName();

        // Already running?
        var existing = runningProcesses.get(name);
        if (existing != null && existing.isAlive()) {
            LOG.info("Service already running: " + name);
            return;
        }

        // Already starting?
        if (isStarting(name)) {
            LOG.info("Service start already in progress: " + name);
            return;
        }

        var progress = new ProcessProgress(name);
        progressMap.put(name, progress);

        var worker = new ProcessWorkerActor(svc, progress);
        ActorRef<ProcessWorkerActor> workerRef = self.createChild("process-" + name, worker);
        workers.put(name, workerRef);

        workerRef.tell(w -> w.doStart(self));
    }

    /**
     * Called by worker via tell() when start operation completes.
     */
    public void onStartComplete(String name, boolean success, Process process, List<String> command) {
        var workerRef = workers.remove(name);
        if (workerRef != null) {
            workerRef.close();
        }
        if (success && process != null) {
            runningProcesses.put(name, process);
            runningCommands.put(name, command);
            LOG.info("Service start recorded: " + name);
        } else {
            runningProcesses.remove(name);
            runningCommands.remove(name);
            LOG.info("Service start failed: " + name);
        }
    }

    /**
     * Get progress for a service start operation.
     */
    public ServiceProgress getProgress(String name) {
        var progress = progressMap.get(name);
        if (progress == null) {
            return ServiceProgress.idle(name);
        }
        return progress.toServiceProgress();
    }

    /**
     * Check if a service is currently in a start operation (not yet done).
     */
    public boolean isStarting(String name) {
        var progress = progressMap.get(name);
        return progress != null && !progress.done;
    }

    /**
     * Stop a service. Returns true if stopped (or already not running).
     */
    public boolean stop(String name, int port) {
        progressMap.remove(name);
        var workerRef = workers.remove(name);
        if (workerRef != null) {
            workerRef.close();
        }

        var process = runningProcesses.remove(name);
        runningCommands.remove(name);

        if (process != null && process.isAlive()) {
            LOG.info("Stopping service: " + name);
            process.destroy();
            try {
                boolean exited = process.waitFor(5, TimeUnit.SECONDS);
                if (!exited) {
                    LOG.warning("Service " + name + " did not stop gracefully, forcing");
                    process.destroyForcibly();
                    process.waitFor(3, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
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
     * Check if a service process is alive.
     * Falls back to port check for processes started outside this Portal instance.
     */
    public ServiceStatus getStatus(String name, int port) {
        if (isStarting(name)) {
            return ServiceStatus.STARTING;
        }

        var process = runningProcesses.get(name);
        if (process != null) {
            if (process.isAlive()) {
                return ServiceStatus.ACTIVE;
            }
            int exitCode = process.exitValue();
            runningProcesses.remove(name);
            runningCommands.remove(name);
            return exitCode == 0 ? ServiceStatus.INACTIVE : ServiceStatus.FAILED;
        }

        // No managed process — check if port is in use (orphan from previous Portal run)
        if (port > 0 && isPortInUse(port)) {
            return ServiceStatus.ACTIVE;
        }
        return ServiceStatus.INACTIVE;
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
                var process = runningProcesses.get(svc.getName());
                if (process != null && process.isAlive()) {
                    var cmd = runningCommands.get(svc.getName());
                    processName = cmd != null ? String.join(" ", cmd) : null;
                } else {
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
     * Get the process name listening on a given port using ss command.
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
            for (var line : output.split("\n")) {
                int usersIdx = line.indexOf("users:((");
                if (usersIdx >= 0) {
                    int start = line.indexOf("\"", usersIdx);
                    int end = line.indexOf("\"", start + 1);
                    if (start >= 0 && end > start) {
                        var procName = line.substring(start + 1, end);
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
     * Stop all running processes on application shutdown.
     */
    public void onShutdown() {
        LOG.info("Shutting down all managed processes");
        for (var name : new ArrayList<>(runningProcesses.keySet())) {
            stop(name, 0);
        }
    }

    // ── Private helpers ──

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

    private boolean isPortInUse(int port) {
        try (var socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("localhost", port), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
