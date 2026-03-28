package com.scivicslab.lxdpups.actor;

import com.scivicslab.lxdpups.config.PortalConfig;
import com.scivicslab.lxdpups.config.PortalConfigLoader;
import com.scivicslab.lxdpups.model.ServiceStatus;
import com.scivicslab.lxdpups.service.BuildManager;
import com.scivicslab.lxdpups.service.ToolInstanceManager;
import com.scivicslab.pojoactor.core.ActorRef;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Supervisor actor for tool lifecycle operations (host-side process tools).
 * Absorbs state previously in ToolInstanceManager (allocatedPorts, memos).
 * Plain HashMaps are safe because the actor's single-threaded message loop
 * ensures sequential access.
 */
public class ToolSupervisorActor {

    private static final Logger LOG = Logger.getLogger(ToolSupervisorActor.class.getName());

    // State absorbed from ToolInstanceManager — plain fields, actor-safe
    private final HashMap<String, List<Integer>> allocatedPorts = new HashMap<>();
    private final HashMap<String, String> memos = new HashMap<>();

    private final PortalConfigLoader configLoader;
    private final ActorRef<ProcessSupervisorActor> processSupervisor;
    private final BuildManager buildManager;

    public ToolSupervisorActor(PortalConfigLoader configLoader,
                               ActorRef<ProcessSupervisorActor> processSupervisor,
                               BuildManager buildManager) {
        this.configLoader = configLoader;
        this.processSupervisor = processSupervisor;
        this.buildManager = buildManager;
    }

    /**
     * Launch a new instance of a tool with optional working directory and extra params.
     * Returns the port number used, or -1 if no port was available or tool unknown.
     */
    public int launchTool(String toolName, String workDir, Map<String, String> extraParams) {
        var tool = findTool(toolName);
        if (tool == null) {
            LOG.warning("Unknown tool: " + toolName);
            return -1;
        }

        int port = findAvailablePort(tool);
        if (port < 0) {
            LOG.warning("No available port in range " + tool.getPortRange() + " for tool " + toolName);
            return -1;
        }

        var svc = toManagementService(tool, port, extraParams);
        if (workDir != null && !workDir.isEmpty() && svc.getBinary() != null) {
            svc.getBinary().setWorkDir(workDir);
        }

        allocatedPorts.computeIfAbsent(toolName, k -> new ArrayList<>()).add(port);
        processSupervisor.tell(s -> s.startAsync(processSupervisor, svc));

        LOG.info("Launched tool " + toolName + " on port " + port
                + (workDir != null ? " workDir=" + workDir : ""));
        return port;
    }

    /**
     * Stop a specific tool instance by name and port.
     */
    public boolean stopTool(String toolName, int port) {
        var instanceName = toolName + "-" + port;
        boolean ok = processSupervisor.ask(s -> s.stop(instanceName, port)).join();

        var ports = allocatedPorts.get(toolName);
        if (ports != null) {
            ports.remove(Integer.valueOf(port));
        }
        memos.remove(instanceName);

        if (ok) {
            LOG.info("Stopped tool '" + toolName + "' on port " + port);
        } else {
            LOG.warning("Failed to stop tool '" + toolName + "' on port " + port);
        }
        return ok;
    }

    /**
     * Update the memo for a running tool instance.
     */
    public void updateMemo(String toolName, int port, String memo) {
        var key = toolName + "-" + port;
        if (memo == null || memo.isBlank()) {
            memos.remove(key);
        } else {
            memos.put(key, memo.strip());
        }
    }

    /**
     * Get all running tool instances across all tools.
     */
    public List<ToolInstanceManager.ToolInstance> getRunningInstances() {
        var result = new ArrayList<ToolInstanceManager.ToolInstance>();
        for (var tool : configLoader.getConfig().getTools()) {
            int portStart = tool.getPortStart();
            int portEnd = tool.getPortEnd();
            for (int port = portStart; port <= portEnd; port++) {
                var instanceName = tool.getName() + "-" + port;
                final int finalPort = port;
                var status = processSupervisor.ask(s -> s.getStatus(instanceName, finalPort)).join();
                if (status == ServiceStatus.ACTIVE || status == ServiceStatus.STARTING) {
                    String processName = null;
                    if (status == ServiceStatus.ACTIVE) {
                        final int p = port;
                        processName = processSupervisor.ask(s -> s.getProcessNameOnPort(p)).join();
                    }
                    var key = tool.getName() + "-" + port;
                    result.add(new ToolInstanceManager.ToolInstance(
                            tool.getName(), tool.getDescription(), tool.getIcon(),
                            port, status, processName, tool.getUiPath(),
                            memos.getOrDefault(key, "")));
                }
            }
        }
        return result;
    }

    /**
     * Start a build for a tool from source.
     */
    public void buildTool(String name) {
        boolean started = buildManager.startBuild(name);
        if (started) {
            LOG.info("Build started for tool '" + name + "'");
        } else {
            LOG.warning("Failed to start build for tool '" + name + "' — already building or unknown tool");
        }
    }

    // ── Private helpers ──

    private int findAvailablePort(PortalConfig.ToolDefinition tool) {
        int portStart = tool.getPortStart();
        int portEnd = tool.getPortEnd();
        if (portStart == 0 && portEnd == 0) return -1;
        for (int port = portStart; port <= portEnd; port++) {
            var instanceName = tool.getName() + "-" + port;
            final int finalPort = port;
            var status = processSupervisor.ask(s -> s.getStatus(instanceName, finalPort)).join();
            if (status == ServiceStatus.INACTIVE) {
                return port;
            }
        }
        return -1;
    }

    private PortalConfig.ManagementService toManagementService(PortalConfig.ToolDefinition tool, int port,
                                                                Map<String, String> extraParams) {
        var svc = new PortalConfig.ManagementService();
        svc.setName(tool.getName() + "-" + port);
        svc.setPort(port);
        svc.setDescription(tool.getDescription());
        svc.setEnabled(true);

        if (tool.getBinary() != null) {
            var srcBin = tool.getBinary();
            var bin = new PortalConfig.ManagementService.Binary();
            bin.setRepo(srcBin.getRepo());
            bin.setVersion(srcBin.getVersion());
            bin.setAsset(srcBin.getAsset());
            bin.setPath(srcBin.getPath());
            bin.setRuntime(srcBin.getRuntime());
            bin.setWorkDir(srcBin.getWorkDir());
            if (srcBin.getArgs() != null) {
                String args = srcBin.getArgs().replace("{port}", String.valueOf(port));
                for (var entry : extraParams.entrySet()) {
                    args = args.replace("{" + entry.getKey() + "}", entry.getValue());
                }
                bin.setArgs(args);
            }
            svc.setBinary(bin);
        }

        return svc;
    }

    private PortalConfig.ToolDefinition findTool(String name) {
        return configLoader.getConfig().getTools().stream()
                .filter(t -> name.equals(t.getName()))
                .findFirst()
                .orElse(null);
    }
}
