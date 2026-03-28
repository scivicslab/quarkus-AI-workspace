package com.scivicslab.lxdpups.actor;

import com.scivicslab.lxdpups.config.PortalConfig;
import com.scivicslab.lxdpups.service.ProcessManager;
import com.scivicslab.pojoactor.core.ActorRef;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Worker actor that executes a single tool build operation (git clone + mvn package -Dnative).
 * One instance per build; created as child of BuildSupervisorActor.
 * No Semaphore needed — BuildSupervisorActor enforces single-build at a time via actor queue.
 */
public class BuildWorkerActor {

    private static final Logger LOG = Logger.getLogger(BuildWorkerActor.class.getName());
    private static final String BUILD_BASE = "/var/tmp/lxd-pups-build";

    private final PortalConfig.ToolDefinition tool;
    private final BuildProgress progress;

    public BuildWorkerActor(PortalConfig.ToolDefinition tool, BuildProgress progress) {
        this.tool = tool;
        this.progress = progress;
    }

    /**
     * Execute the full build sequence: git clone/pull + mvn package -Dnative + install.
     * Called via tell() from BuildSupervisorActor.
     * On completion, calls supervisor.tell(s -> s.onBuildComplete(name, success)).
     */
    public void doBuild(ActorRef<BuildSupervisorActor> supervisor) {
        var name = tool.getName();
        var binary = tool.getBinary();
        var buildDir = Path.of(BUILD_BASE, binary.getBuildDir());
        var repoUrl = "https://github.com/" + binary.getRepo() + ".git";

        // Phase: cloning
        progress.setPhase("cloning");
        try {
            if (Files.exists(buildDir.resolve(".git"))) {
                progress.addMessage("Source exists, pulling latest...");
                if (!runProcess(progress, buildDir, "git", "pull", "--ff-only")) {
                    progress.complete(false);
                    supervisor.tell(s -> s.onBuildComplete(name, false));
                    return;
                }
            } else {
                progress.addMessage("Cloning " + repoUrl + " ...");
                Files.createDirectories(buildDir.getParent());
                if (!runProcess(progress, buildDir.getParent(), "git", "clone", repoUrl, binary.getBuildDir())) {
                    progress.complete(false);
                    supervisor.tell(s -> s.onBuildComplete(name, false));
                    return;
                }
            }

            // Show latest commit
            runProcess(progress, buildDir, "git", "log", "--oneline", "-1");

            // Phase: building
            progress.setPhase("building");
            progress.addMessage("Building native image (this may take several minutes)...");

            var targetDir = buildDir.resolve("target");
            if (Files.exists(targetDir)) {
                runProcess(progress, buildDir, "rm", "-rf", "target");
            }

            var buildCommand = "source /opt/sdkman/bin/sdkman-init.sh && mvn package -Dnative -DskipTests";
            if (!runProcess(progress, buildDir, "bash", "-c", buildCommand)) {
                progress.addMessage("Native build failed.");
                progress.complete(false);
                supervisor.tell(s -> s.onBuildComplete(name, false));
                return;
            }

            // Phase: installing
            progress.setPhase("installing");
            var runner = findRunner(buildDir);
            if (runner == null) {
                progress.addMessage("Native binary (*-runner) not found in target/");
                progress.complete(false);
                supervisor.tell(s -> s.onBuildComplete(name, false));
                return;
            }

            var destPath = ProcessManager.resolvePath(binary.getPath());
            progress.addMessage("Installing " + runner.getFileName() + " -> " + destPath);

            var destDir = Path.of(destPath).getParent();
            if (destDir != null) {
                Files.createDirectories(destDir);
            }

            Files.copy(runner, Path.of(destPath), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Path.of(destPath).toFile().setExecutable(true);

            var size = Files.size(Path.of(destPath));
            progress.addMessage("Installed: " + String.format("%.1f MB", size / (1024.0 * 1024.0)));

            progress.setPhase("done");
            progress.addMessage("Build complete.");
            progress.complete(true);
            LOG.info("Build succeeded for " + tool.getName());
            supervisor.tell(s -> s.onBuildComplete(name, true));

        } catch (Exception e) {
            progress.addMessage("Unexpected error: " + e.getMessage());
            progress.complete(false);
            LOG.warning("Build failed for " + name + ": " + e.getMessage());
            supervisor.tell(s -> s.onBuildComplete(name, false));
        }
    }

    /**
     * Run an external process, streaming stdout/stderr to progress.
     * Returns true if exit code is 0.
     */
    private boolean runProcess(BuildProgress progress, Path workDir, String... command) {
        try {
            var pb = new ProcessBuilder(command);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);
            var process = pb.start();

            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    progress.addMessage(line);
                }
            }

            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            progress.addMessage("Process error: " + e.getMessage());
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
}
