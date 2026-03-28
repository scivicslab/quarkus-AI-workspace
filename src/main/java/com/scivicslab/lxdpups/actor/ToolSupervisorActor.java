package com.scivicslab.lxdpups.actor;

import com.scivicslab.lxdpups.service.BuildManager;
import com.scivicslab.lxdpups.service.ToolInstanceManager;

import java.util.logging.Logger;

/**
 * Supervisor actor for tool lifecycle operations (host-side process tools).
 * <p>
 * Wraps {@link ToolInstanceManager} and {@link BuildManager} as an actor so that
 * Kafka commands and REST requests both arrive via the actor message queue — giving
 * sequential, race-free processing without explicit locking.
 * </p>
 *
 * <p>All methods are invoked via {@code ActorRef.tell()}, so callers never block
 * waiting for the operation to complete.</p>
 *
 * <p>Actor hierarchy:
 * <pre>
 *   ActorSystem("lxd-pups")
 *     └─ ToolSupervisorActor("tool-supervisor")
 * </pre>
 * </p>
 */
public class ToolSupervisorActor {

    private static final Logger LOG = Logger.getLogger(ToolSupervisorActor.class.getName());

    private final ToolInstanceManager toolInstanceManager;
    private final BuildManager buildManager;

    public ToolSupervisorActor(ToolInstanceManager toolInstanceManager, BuildManager buildManager) {
        this.toolInstanceManager = toolInstanceManager;
        this.buildManager = buildManager;
    }

    /**
     * Launch a new instance of a tool, optionally with a working directory.
     * Finds the next available port in the tool's configured range.
     */
    public void launchTool(String name, String workDir) {
        int port = toolInstanceManager.launchTool(name, workDir);
        if (port >= 0) {
            LOG.info("Launched tool '" + name + "' on port " + port
                    + (workDir != null ? " workDir=" + workDir : ""));
        } else {
            LOG.warning("Failed to launch tool '" + name + "' — no available port or unknown tool");
        }
    }

    /**
     * Stop a running tool instance identified by name and port.
     */
    public void stopTool(String name, int port) {
        boolean ok = toolInstanceManager.stopTool(name, port);
        if (ok) {
            LOG.info("Stopped tool '" + name + "' on port " + port);
        } else {
            LOG.warning("Failed to stop tool '" + name + "' on port " + port);
        }
    }

    /**
     * Start a build for a tool from source.
     * Only one concurrent build is allowed (BuildManager enforces this).
     */
    public void buildTool(String name) {
        boolean started = buildManager.startBuild(name);
        if (started) {
            LOG.info("Build started for tool '" + name + "'");
        } else {
            LOG.warning("Failed to start build for tool '" + name + "' — already building or unknown tool");
        }
    }
}
