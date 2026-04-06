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
            boolean hasUrl  = binary.getUrl()  != null && !binary.getUrl().isBlank();
            if (hasPath && !Files.exists(Path.of(resolvedPath))) {
                if (hasUrl) {
                    // Direct URL download + tarball extraction (e.g. Apache Kafka)
                    progress.setPhase("downloading");
                    progress.addMessage("Downloading from " + binary.getUrl() + " ...");
                    try {
                        ProcessManager.downloadAndExtract(binary, progress);
                    } catch (Exception e) {
                        progress.addMessage("Download failed: " + e.getMessage());
                        progress.complete(false);
                        supervisor.tell(s -> s.onStartComplete(name, false, null, List.of()));
                        return;
                    }
                    // Run post-install command if specified (e.g. Kafka KRaft storage setup)
                    var postCmd = binary.getPostInstallCmd();
                    if (postCmd != null && !postCmd.isBlank()) {
                        progress.setPhase("installing");
                        progress.addMessage("Running post-install: " + postCmd);
                        try {
                            var pb = new ProcessBuilder("bash", "-c",
                                    postCmd.replace("~", System.getProperty("user.home")));
                            pb.redirectErrorStream(true);
                            var proc = pb.start();
                            var out = new String(proc.getInputStream().readAllBytes());
                            int exit = proc.waitFor();
                            if (!out.isBlank()) progress.addMessage(out.strip());
                            if (exit != 0) throw new RuntimeException("post-install exited with code " + exit);
                        } catch (Exception e) {
                            progress.addMessage("Post-install failed: " + e.getMessage());
                            progress.complete(false);
                            supervisor.tell(s -> s.onStartComplete(name, false, null, List.of()));
                            return;
                        }
                    }
                    progress.addMessage("Installation complete.");
                } else if (hasRepo) {
                    // GitHub Release download
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
                } else {
                    progress.addMessage("Binary not found at " + resolvedPath + " (no download source configured)");
                    progress.complete(false);
                    supervisor.tell(s -> s.onStartComplete(name, false, null, List.of()));
                    return;
                }
            } else if (hasPath) {
                progress.addMessage("Binary already exists at " + resolvedPath);
            }

            // Build command and launch
            progress.setPhase("launching");
            command = ProcessManager.buildCommand(svc);
            final var finalCommand = command;
            progress.addMessage("Launching: " + String.join(" ", command));

            // Wrap with setsid so the child runs in a new process group and
            // survives lxd-pups restarts (not killed when parent JVM exits).
            var detachedCommand = new java.util.ArrayList<String>();
            detachedCommand.add("setsid");
            detachedCommand.addAll(command);

            var pb = new ProcessBuilder(detachedCommand);
            var workDir = (binary.getWorkDir() != null && !binary.getWorkDir().isEmpty())
                    ? ProcessManager.resolvePath(binary.getWorkDir())
                    : System.getProperty("user.home");
            pb.directory(new java.io.File(workDir));
            progress.addMessage("Working directory: " + workDir);
            pb.environment().put("QUARKUS_HTTP_PORT", String.valueOf(svc.getPort()));
            pb.environment().put("QUARKUS_HTTP_HOST", "0.0.0.0");
            // Pass JAVA_HOME so scripts like kafka-server-start.sh can find the JVM
            var javaHome = System.getProperty("java.home");
            if (javaHome != null) {
                pb.environment().put("JAVA_HOME", javaHome);
                var currentPath = pb.environment().getOrDefault("PATH", "/usr/bin:/bin");
                pb.environment().put("PATH", javaHome + "/bin:" + currentPath);
            }
            ProcessManager.appendNvmToPath(pb);
            var logFile = new java.io.File(System.getProperty("user.home") + "/.lxd-pups/" + name + ".log");
            pb.redirectErrorStream(true);
            pb.redirectOutput(logFile);
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
