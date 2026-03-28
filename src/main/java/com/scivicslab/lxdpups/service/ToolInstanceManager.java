package com.scivicslab.lxdpups.service;

import com.scivicslab.lxdpups.actor.LxdPupsActorSystem;
import com.scivicslab.lxdpups.config.PortalConfig;
import com.scivicslab.lxdpups.config.PortalConfigLoader;
import com.scivicslab.lxdpups.model.ServiceStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Thin CDI facade delegating to ToolSupervisorActor.
 * State (allocatedPorts, memos) has moved to ToolSupervisorActor.
 */
@ApplicationScoped
public class ToolInstanceManager {

    private static final Logger LOG = Logger.getLogger(ToolInstanceManager.class.getName());

    @Inject
    LxdPupsActorSystem actorSystem;

    @Inject
    PortalConfigLoader configLoader;

    /**
     * A running tool instance.
     */
    public record ToolInstance(String toolName, String description, String icon, int port,
                               ServiceStatus status, String processName, String uiPath,
                               String memo) {}

    public int launchTool(String toolName) {
        return launchTool(toolName, null);
    }

    public int launchTool(String toolName, String workDir) {
        return launchTool(toolName, workDir, Map.of());
    }

    public int launchTool(String toolName, String workDir, Map<String, String> extraParams) {
        return actorSystem.getToolSupervisor().ask(t -> t.launchTool(toolName, workDir, extraParams)).join();
    }

    public boolean stopTool(String toolName, int port) {
        return actorSystem.getToolSupervisor().ask(t -> t.stopTool(toolName, port)).join();
    }

    public void updateMemo(String toolName, int port, String memo) {
        actorSystem.getToolSupervisor().tell(t -> t.updateMemo(toolName, port, memo));
    }

    public List<ToolInstance> getRunningInstances() {
        return actorSystem.getToolSupervisor().ask(t -> t.getRunningInstances()).join();
    }

    /**
     * Testable variant for port availability check.
     * Pure function — no actor system needed.
     */
    int findAvailablePortWith(PortalConfig.ToolDefinition tool, ProcessManager pm) {
        int portStart = tool.getPortStart();
        int portEnd = tool.getPortEnd();
        if (portStart == 0 && portEnd == 0) return -1;
        for (int port = portStart; port <= portEnd; port++) {
            var instanceName = tool.getName() + "-" + port;
            var status = pm.getStatus(instanceName, port);
            if (status == ServiceStatus.INACTIVE) {
                return port;
            }
        }
        return -1;
    }
}
