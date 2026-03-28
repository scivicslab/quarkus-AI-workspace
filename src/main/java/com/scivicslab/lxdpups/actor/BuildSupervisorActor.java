package com.scivicslab.lxdpups.actor;

import com.scivicslab.lxdpups.config.PortalConfig;
import com.scivicslab.lxdpups.config.PortalConfigLoader;
import com.scivicslab.lxdpups.model.ServiceProgress;
import com.scivicslab.pojoactor.core.ActorRef;

import java.util.HashMap;
import java.util.logging.Logger;

/**
 * Supervisor actor for tool builds.
 * Replaces BuildManager's Semaphore and ConcurrentHashMap with actor-sequential state.
 * The actor's single-threaded message queue replaces the Semaphore:
 * only one tell() runs at a time, so activeBuilds.isEmpty() check is race-free.
 */
public class BuildSupervisorActor {

    private static final Logger LOG = Logger.getLogger(BuildSupervisorActor.class.getName());

    // State — plain fields, safe because actor is single-threaded
    private final HashMap<String, ActorRef<BuildWorkerActor>> activeBuilds = new HashMap<>();
    private final HashMap<String, BuildProgress> progressMap = new HashMap<>();
    private final PortalConfigLoader configLoader;

    public BuildSupervisorActor(PortalConfigLoader configLoader) {
        this.configLoader = configLoader;
    }

    /**
     * Start a build for the given tool.
     * Returns true if the build was accepted, false if:
     * - another build is already running (only one at a time)
     * - tool is unknown or not buildable
     */
    public boolean startBuild(ActorRef<BuildSupervisorActor> self, String toolName) {
        // Only one build at a time — actor queue makes this check race-free
        if (!activeBuilds.isEmpty()) {
            LOG.info("Build rejected — another build is in progress");
            return false;
        }

        var tool = findTool(toolName);
        if (tool == null || tool.getBinary() == null || tool.getBinary().getBuildDir() == null) {
            LOG.warning("Build rejected — unknown or non-buildable tool: " + toolName);
            return false;
        }

        // Reject if already building this tool (build not yet done)
        var existing = progressMap.get(toolName);
        if (existing != null && !existing.done) {
            return false;
        }

        var progress = new BuildProgress(toolName);
        progressMap.put(toolName, progress);

        var worker = new BuildWorkerActor(tool, progress);
        ActorRef<BuildWorkerActor> workerRef = self.createChild("build-" + toolName, worker);
        activeBuilds.put(toolName, workerRef);

        workerRef.tell(w -> w.doBuild(self));
        return true;
    }

    /**
     * Called by worker via tell() when build completes.
     */
    public void onBuildComplete(String name, boolean success) {
        var workerRef = activeBuilds.remove(name);
        if (workerRef != null) {
            workerRef.close();
        }
        LOG.info("Build completed: " + name + " success=" + success);
    }

    /**
     * Get build progress for a tool.
     */
    public ServiceProgress getProgress(String toolName) {
        var progress = progressMap.get(toolName);
        if (progress == null) {
            return ServiceProgress.idle(toolName);
        }
        return progress.toServiceProgress();
    }

    /**
     * Check if a build is currently in progress for this tool.
     */
    public boolean isBuilding(String toolName) {
        var progress = progressMap.get(toolName);
        return progress != null && !progress.done;
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
