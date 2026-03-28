package com.scivicslab.lxdpups.actor;

import com.scivicslab.lxdpups.config.PortalConfig;
import com.scivicslab.lxdpups.service.ProcessManager;
import com.scivicslab.pojoactor.core.ActorRef;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

/**
 * Worker actor that executes a single service start operation.
 * One instance per service-start request; created as child of ProcessSupervisorActor.
 * Runs blocking work (download + launch) in the actor's virtual thread.
 */
public class ProcessWorkerActor {

    private static final Logger LOG = Logger.getLogger(ProcessWorkerActor.class.getName());

    private final PortalConfig.ManagementService svc;
    private final ProcessProgress progress;
    Process process;

    public ProcessWorkerActor(PortalConfig.ManagementService svc, ProcessProgress progress) {
        this.svc = svc;
        this.progress = progress;
    }

    /**
     * Execute the full service start sequence: download (if needed) + launch.
     * Called via tell() from ProcessSupervisorActor.
     * On completion, calls supervisor.tell(s -> s.onStartComplete(...)).
     */
    public void doStart(ActorRef<ProcessSupervisorActor> supervisor) {
        var name = svc.getName();
        var binary = svc.getBinary();
        List<String> command = null;
        boolean success = false;
        Process launchedProcess = null;

        try {
            if (binary == null) {
                progress.addMessage("No binary configuration found.");
                progress.complete(false);
                supervisor.tell(s -> s.onStartComplete(name, false, null, List.of()));
                return;
            }

            // Download binary if not present
            var resolvedPath = ProcessManager.resolvePath(binary.getPath());
            boolean hasPath = resolvedPath != null && !resolvedPath.isEmpty();
            boolean hasRepo = binary.getRepo() != null && !binary.getRepo().isEmpty();
            if (hasPath && !Files.exists(Path.of(resolvedPath)) && hasRepo) {
                progress.setPhase("downloading");
                progress.addMessage("Downloading from " + binary.getRepo() + " " + binary.getVersion() + " ...");
                try {
                    ProcessManager.downloadBinary(binary, progress);
                } catch (Exception e) {
                    progress.addMessage("Download failed: " + e.getMessage());
                    progress.complete(false);
                    supervisor.tell(s -> s.onStartComplete(name, false, null, List.of()));
                    return;
                }
                progress.addMessage("Download complete.");
            } else if (hasPath && !Files.exists(Path.of(resolvedPath))) {
                progress.addMessage("Binary not found at " + resolvedPath + " (no repo configured for download)");
                progress.complete(false);
                supervisor.tell(s -> s.onStartComplete(name, false, null, List.of()));
                return;
            } else if (hasPath) {
                progress.addMessage("Binary already exists at " + resolvedPath);
            }

            // Build command and launch
            progress.setPhase("launching");
            command = ProcessManager.buildCommand(svc);
            final var finalCommand = command;
            progress.addMessage("Launching: " + String.join(" ", command));

            var pb = new ProcessBuilder(command);
            var workDir = (binary.getWorkDir() != null && !binary.getWorkDir().isEmpty())
                    ? ProcessManager.resolvePath(binary.getWorkDir())
                    : System.getProperty("user.home");
            pb.directory(new java.io.File(workDir));
            progress.addMessage("Working directory: " + workDir);
            pb.environment().put("QUARKUS_HTTP_PORT", String.valueOf(svc.getPort()));
            pb.environment().put("QUARKUS_HTTP_HOST", "0.0.0.0");
            ProcessManager.appendNvmToPath(pb);
            pb.redirectErrorStream(true);
            pb.inheritIO();
            launchedProcess = pb.start();
            this.process = launchedProcess;

            // Wait briefly to check if process crashes immediately
            Thread.sleep(2000);

            if (launchedProcess.isAlive()) {
                progress.addMessage("Service started (pid " + launchedProcess.pid() + ").");
                progress.setPhase("running");
                progress.complete(true);
                success = true;
                LOG.info("Started service " + name + " (pid " + launchedProcess.pid() + ")");
            } else {
                int exitCode = launchedProcess.exitValue();
                progress.addMessage("Process exited immediately with code " + exitCode + ".");
                progress.complete(false);
                launchedProcess = null;
            }

            final var resultProcess = launchedProcess;
            final var resultCommand = finalCommand;
            final boolean resultSuccess = success;
            supervisor.tell(s -> s.onStartComplete(name, resultSuccess, resultProcess, resultCommand));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            progress.addMessage("Interrupted while starting.");
            progress.complete(false);
            supervisor.tell(s -> s.onStartComplete(name, false, null, List.of()));
        } catch (Exception e) {
            progress.addMessage("Error: " + e.getMessage());
            progress.complete(false);
            LOG.warning("Failed to start " + name + ": " + e.getMessage());
            supervisor.tell(s -> s.onStartComplete(name, false, null, List.of()));
        }
    }
}
