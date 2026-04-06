package com.scivicslab.lxdpups.service;

import com.scivicslab.lxdpups.actor.LxdPupsActorSystem;
import com.scivicslab.lxdpups.actor.ProcessProgress;
import com.scivicslab.lxdpups.config.PortalConfig;
import com.scivicslab.lxdpups.model.HostService;
import com.scivicslab.lxdpups.model.ServiceProgress;
import com.scivicslab.lxdpups.model.ServiceStatus;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Thin CDI facade delegating to ProcessSupervisorActor.
 * Static helper methods (resolvePath, buildCommand, appendNvmToPath, etc.)
 * are kept here for use by ProcessWorkerActor and BuildWorkerActor.
 */
@ApplicationScoped
public class ProcessManager {

    private static final Logger LOG = Logger.getLogger(ProcessManager.class.getName());

    @Inject
    LxdPupsActorSystem actorSystem;

    // ── CDI facade methods ──

    /**
     * Start a management service asynchronously (fire-and-forget).
     */
    public void startAsync(PortalConfig.ManagementService svc) {
        var sup = actorSystem.getProcessSupervisor();
        sup.tell(s -> s.startAsync(sup, svc));
    }

    /**
     * Synchronous start kept for backward compatibility.
     * Delegates to startAsync and returns true (fire-and-forget).
     */
    public boolean start(PortalConfig.ManagementService svc) {
        startAsync(svc);
        return true;
    }

    public boolean stop(String name, int port) {
        return actorSystem.getProcessSupervisor().ask(s -> s.stop(name, port)).join();
    }

    public boolean stop(String name) {
        return stop(name, 0);
    }

    public ServiceProgress getProgress(String name) {
        if (actorSystem == null) {
            return ServiceProgress.idle(name);
        }
        return actorSystem.getProcessSupervisor().ask(s -> s.getProgress(name)).join();
    }

    public ServiceStatus getStatus(String name, int port) {
        if (actorSystem == null) {
            return ServiceStatus.STOPPED;
        }
        return actorSystem.getProcessSupervisor().ask(s -> s.getStatus(name, port)).join();
    }

    public boolean isStarting(String name) {
        if (actorSystem == null) {
            return false;
        }
        return actorSystem.getProcessSupervisor().ask(s -> s.isStarting(name)).join();
    }

    public List<HostService> getAllStatuses(List<PortalConfig.ManagementService> services) {
        if (actorSystem == null) {
            // Unit test path: return statuses using local logic (no actor)
            var result = new ArrayList<HostService>();
            for (var svc : services) {
                if (!svc.isEnabled()) continue;
                result.add(new HostService(
                        svc.getName(), svc.getUnit(), svc.getPort(),
                        svc.getDescription(), svc.getUi(), ServiceStatus.STOPPED, null));
            }
            return result;
        }
        return actorSystem.getProcessSupervisor().ask(s -> s.getAllStatuses(services)).join();
    }

    public String getProcessNameOnPort(int port) {
        if (actorSystem == null) {
            return null;
        }
        return actorSystem.getProcessSupervisor().ask(s -> s.getProcessNameOnPort(port)).join();
    }

    void onShutdown(@Observes ShutdownEvent event) {
        actorSystem.getProcessSupervisor().ask(s -> { s.onShutdown(); return null; }).join();
    }

    // ── Static helper methods (used by ProcessWorkerActor, BuildWorkerActor) ──

    /**
     * Build the launch command for a service.
     */
    public static List<String> buildCommand(PortalConfig.ManagementService svc) {
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
                    var resolved = resolvePath(arg);
                    if (arg.startsWith("-D") || arg.startsWith("-X") || arg.startsWith("-javaagent")) {
                        command.add(resolved);
                    } else {
                        postJarArgs.add(resolved);
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

        // Append extra arguments if specified (expand ~ in path-like args)
        if (binary.getArgs() != null && !binary.getArgs().isBlank()) {
            for (var arg : binary.getArgs().split("\\s+")) {
                command.add(resolvePath(arg));
            }
        }

        return command;
    }

    /**
     * Append NVM node bin directory to PATH if ~/.nvm exists.
     */
    public static void appendNvmToPath(ProcessBuilder pb) {
        var home = System.getProperty("user.home");
        var nvmDir = Path.of(home, ".nvm", "versions", "node");
        if (!Files.isDirectory(nvmDir)) return;
        try (var versions = Files.list(nvmDir)) {
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
    public static String resolvePath(String path) {
        if (path != null && path.startsWith("~/")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }

    /**
     * Resolve a runtime command (e.g. "yarn", "npx") to its full path.
     */
    static String resolveRuntime(String runtime) {
        if (runtime == null) return null;
        if (runtime.startsWith("/")) return runtime;
        var nvmBin = findNvmBinDir();
        if (nvmBin != null) {
            var candidate = nvmBin.resolve(runtime);
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
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
     * Download binary from GitHub Release using curl.
     * Captures stderr progress output and feeds it to the ProcessProgress in real-time.
     * Package-private so ProcessWorkerActor can call it.
     */
    public static void downloadBinary(PortalConfig.ManagementService.Binary binary, ProcessProgress tracker) throws Exception {
        var url = String.format("https://github.com/%s/releases/download/%s/%s",
                binary.getRepo(), binary.getVersion(), binary.getAsset());
        var resolvedPath = resolvePath(binary.getPath());

        var parentDir = Path.of(resolvedPath).getParent();
        if (parentDir != null) {
            Files.createDirectories(parentDir);
        }

        LOG.info("Downloading " + url + " to " + resolvedPath);
        if (tracker != null) {
            tracker.addMessage("URL: " + url);
            tracker.addMessage("Destination: " + resolvedPath);
        }

        var pb = new ProcessBuilder("curl", "-fL", "-o", resolvedPath, "--progress-bar", url);
        pb.redirectErrorStream(true);
        var process = pb.start();

        // Read stdout+stderr (curl progress goes to stderr, merged here)
        // This sub-thread is intentional — reads curl progress output concurrently
        Thread.ofVirtual().name("curl-progress-" + binary.getAsset()).start(() -> {
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    var trimmed = line.trim();
                    if (!trimmed.isEmpty() && tracker != null) {
                        tracker.updateDownloadLine(trimmed);
                    }
                }
            } catch (Exception e) {
                // ignore read errors on process exit
            }
        });

        boolean finished = process.waitFor(300, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("Download timed out after 300s");
        }
        if (process.exitValue() != 0) {
            throw new RuntimeException("curl exited with code " + process.exitValue());
        }

        var fileSize = Files.size(Path.of(resolvedPath));
        var sizeMB = String.format("%.1f MB", fileSize / (1024.0 * 1024.0));
        if (tracker != null) tracker.addMessage("Downloaded " + sizeMB);

        if (binary.getRuntime() == null) {
            Path.of(resolvedPath).toFile().setExecutable(true);
            if (tracker != null) tracker.addMessage("Set executable permission.");
        }
    }

    /**
     * Download a tarball from a direct URL and extract it to installDir.
     * Uses curl + tar --strip-components=1 (assumes single top-level dir in archive).
     * Called by ProcessWorkerActor when binary.url is set instead of binary.repo.
     */
    public static void downloadAndExtract(PortalConfig.ManagementService.Binary binary,
                                          ProcessProgress tracker) throws Exception {
        var url        = binary.getUrl();
        var installDir = resolvePath(binary.getInstallDir());
        var archiveName = url.substring(url.lastIndexOf('/') + 1);
        var tmpFile = System.getProperty("java.io.tmpdir") + "/" + archiveName;

        if (tracker != null) {
            tracker.addMessage("URL: " + url);
            tracker.addMessage("Installing to: " + installDir);
        }

        // Download archive
        LOG.info("Downloading " + url + " to " + tmpFile);
        var dlPb = new ProcessBuilder("curl", "-fL", "-o", tmpFile, "--progress-bar", url);
        dlPb.redirectErrorStream(true);
        var dlProcess = dlPb.start();

        Thread.ofVirtual().name("curl-progress-" + archiveName).start(() -> {
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(dlProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    var trimmed = line.trim();
                    if (!trimmed.isEmpty() && tracker != null) tracker.updateDownloadLine(trimmed);
                }
            } catch (Exception e) { /* ignore */ }
        });

        boolean dlDone = dlProcess.waitFor(600, java.util.concurrent.TimeUnit.SECONDS);
        if (!dlDone) { dlProcess.destroyForcibly(); throw new RuntimeException("Download timed out"); }
        if (dlProcess.exitValue() != 0) throw new RuntimeException("curl exited with code " + dlProcess.exitValue());

        var sizeMB = String.format("%.1f MB", Files.size(Path.of(tmpFile)) / (1024.0 * 1024.0));
        if (tracker != null) tracker.addMessage("Downloaded " + sizeMB + ", extracting...");

        // Extract
        Files.createDirectories(Path.of(installDir));
        var tarPb = new ProcessBuilder("tar", "-xzf", tmpFile, "-C", installDir, "--strip-components=1");
        tarPb.redirectErrorStream(true);
        var tarProcess = tarPb.start();
        var tarOutput = new String(tarProcess.getInputStream().readAllBytes());
        int tarExit = tarProcess.waitFor();
        Files.deleteIfExists(Path.of(tmpFile));
        if (tarExit != 0) throw new RuntimeException("tar failed (exit=" + tarExit + "): " + tarOutput);

        if (tracker != null) tracker.addMessage("Extracted to " + installDir);
        LOG.info("Extracted " + archiveName + " to " + installDir);
    }
}
