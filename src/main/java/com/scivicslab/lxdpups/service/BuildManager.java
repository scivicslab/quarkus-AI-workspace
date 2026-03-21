package com.scivicslab.lxdpups.service;

import com.scivicslab.lxdpups.config.PortalConfig;
import com.scivicslab.lxdpups.config.PortalConfigLoader;
import com.scivicslab.lxdpups.model.ServiceProgress;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Builds tools from source (git clone + mvn package -Dnative) inside the container.
 * Build directory: /var/tmp/lxd-pups-build/{build-dir}/
 * Only one concurrent build allowed (native-image is memory-intensive).
 */
@ApplicationScoped
public class BuildManager {

    private static final Logger LOG = Logger.getLogger(BuildManager.class.getName());
    private static final String BUILD_BASE = "/var/tmp/lxd-pups-build";

    // Only one concurrent native build (memory intensive)
    private final Semaphore buildSemaphore = new Semaphore(1);
    private final ConcurrentHashMap<String, BuildTracker> trackers = new ConcurrentHashMap<>();

    @Inject
    PortalConfigLoader configLoader;

    /**
     * Mutable progress tracker for a build operation.
     */
    static class BuildTracker {
        private final String name;
        private volatile String phase = "queued";
        private final CopyOnWriteArrayList<String> messages = new CopyOnWriteArrayList<>();
        private volatile boolean done = false;
        private volatile boolean success = false;
        private final CopyOnWriteArrayList<Consumer<ServiceProgress>> listeners = new CopyOnWriteArrayList<>();

        BuildTracker(String name) { this.name = name; }

        void setPhase(String phase) { this.phase = phase; notifyListeners(); }
        void addMessage(String msg) { messages.add(msg); notifyListeners(); }
        void complete(boolean ok) { this.done = true; this.success = ok; notifyListeners(); }

        void addListener(Consumer<ServiceProgress> listener) { listeners.add(listener); }
        void removeListener(Consumer<ServiceProgress> listener) { listeners.remove(listener); }

        private void notifyListeners() {
            var snapshot = toProgress();
            for (var listener : listeners) {
                try { listener.accept(snapshot); } catch (Exception e) { /* ignore */ }
            }
        }

        ServiceProgress toProgress() {
            return new ServiceProgress(name, phase, List.copyOf(messages), done, success);
        }
    }

    /**
     * Start a build for the given tool. Returns true if build was started, false if already building.
     */
    public boolean startBuild(String toolName) {
        var tool = findTool(toolName);
        if (tool == null || tool.getBinary() == null || tool.getBinary().getBuildDir() == null) {
            return false;
        }

        // Reject if already building this tool
        var existing = trackers.get(toolName);
        if (existing != null && !existing.done) {
            return false;
        }

        var tracker = new BuildTracker(toolName);
        trackers.put(toolName, tracker);

        Thread.ofVirtual().name("build-" + toolName).start(() -> {
            try {
                doBuild(tool, tracker);
            } catch (Exception e) {
                tracker.addMessage("Error: " + e.getMessage());
                tracker.complete(false);
                LOG.warning("Build failed for " + toolName + ": " + e.getMessage());
            }
        });
        return true;
    }

    private void doBuild(PortalConfig.ToolDefinition tool, BuildTracker tracker) {
        var binary = tool.getBinary();
        var buildDir = Path.of(BUILD_BASE, binary.getBuildDir());
        var repoUrl = "https://github.com/" + binary.getRepo() + ".git";

        // Acquire semaphore (only one build at a time)
        tracker.setPhase("queued");
        tracker.addMessage("Waiting for build slot...");
        try {
            buildSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            tracker.addMessage("Interrupted while waiting for build slot.");
            tracker.complete(false);
            return;
        }

        try {
            // Phase: cloning
            tracker.setPhase("cloning");
            if (Files.exists(buildDir.resolve(".git"))) {
                tracker.addMessage("Source exists, pulling latest...");
                if (!runProcess(tracker, buildDir, "git", "pull", "--ff-only")) {
                    tracker.complete(false);
                    return;
                }
            } else {
                tracker.addMessage("Cloning " + repoUrl + " ...");
                Files.createDirectories(buildDir.getParent());
                if (!runProcess(tracker, buildDir.getParent(), "git", "clone", repoUrl, binary.getBuildDir())) {
                    tracker.complete(false);
                    return;
                }
            }

            // Show latest commit
            runProcess(tracker, buildDir, "git", "log", "--oneline", "-1");

            // Phase: building
            tracker.setPhase("building");
            tracker.addMessage("Building native image (this may take several minutes)...");

            // Clean old target
            var targetDir = buildDir.resolve("target");
            if (Files.exists(targetDir)) {
                runProcess(tracker, buildDir, "rm", "-rf", "target");
            }

            // Source SDKMAN and build
            var buildCommand = "source /opt/sdkman/bin/sdkman-init.sh && mvn package -Dnative -DskipTests";
            if (!runProcess(tracker, buildDir, "bash", "-c", buildCommand)) {
                tracker.addMessage("Native build failed.");
                tracker.complete(false);
                return;
            }

            // Phase: installing
            tracker.setPhase("installing");
            // Find the native binary (*-runner)
            var runner = findRunner(buildDir);
            if (runner == null) {
                tracker.addMessage("Native binary (*-runner) not found in target/");
                tracker.complete(false);
                return;
            }

            var destPath = ProcessManager.resolvePath(binary.getPath());
            tracker.addMessage("Installing " + runner.getFileName() + " -> " + destPath);

            // Ensure destination directory exists
            var destDir = Path.of(destPath).getParent();
            if (destDir != null) {
                Files.createDirectories(destDir);
            }

            // Copy binary
            Files.copy(runner, Path.of(destPath), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Path.of(destPath).toFile().setExecutable(true);

            var size = Files.size(Path.of(destPath));
            tracker.addMessage("Installed: " + String.format("%.1f MB", size / (1024.0 * 1024.0)));

            // Done
            tracker.setPhase("done");
            tracker.addMessage("Build complete.");
            tracker.complete(true);
            LOG.info("Build succeeded for " + tool.getName());

        } catch (Exception e) {
            tracker.addMessage("Unexpected error: " + e.getMessage());
            tracker.complete(false);
        } finally {
            buildSemaphore.release();
        }
    }

    /**
     * Run an external process, streaming stdout/stderr to the tracker.
     * Returns true if exit code is 0.
     */
    private boolean runProcess(BuildTracker tracker, Path workDir, String... command) {
        try {
            var pb = new ProcessBuilder(command);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);
            var process = pb.start();

            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    tracker.addMessage(line);
                }
            }

            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            tracker.addMessage("Process error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Find the native runner binary in target/ directory.
     */
    private Path findRunner(Path buildDir) {
        var targetDir = buildDir.resolve("target");
        if (!Files.exists(targetDir)) return null;
        try (var stream = Files.list(targetDir)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith("-runner"))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- Progress API ----

    public ServiceProgress getProgress(String toolName) {
        var tracker = trackers.get(toolName);
        if (tracker == null) {
            return ServiceProgress.idle(toolName);
        }
        return tracker.toProgress();
    }

    public void addProgressListener(String toolName, Consumer<ServiceProgress> listener) {
        var tracker = trackers.get(toolName);
        if (tracker != null) {
            tracker.addListener(listener);
        }
    }

    public void removeProgressListener(String toolName, Consumer<ServiceProgress> listener) {
        var tracker = trackers.get(toolName);
        if (tracker != null) {
            tracker.removeListener(listener);
        }
    }

    public boolean isBuilding(String toolName) {
        var tracker = trackers.get(toolName);
        return tracker != null && !tracker.done;
    }

    /**
     * Check if a tool supports building from source (has build-dir configured).
     */
    public boolean isBuildable(String toolName) {
        var tool = findTool(toolName);
        return tool != null && tool.getBinary() != null && tool.getBinary().getBuildDir() != null;
    }

    private PortalConfig.ToolDefinition findTool(String name) {
        return configLoader.getConfig().getTools().stream()
                .filter(t -> name.equals(t.getName()))
                .findFirst()
                .orElse(null);
    }
}
