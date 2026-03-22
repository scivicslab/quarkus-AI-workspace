package com.scivicslab.lxdpups.service;

import com.scivicslab.lxdpups.config.PortalConfig;
import com.scivicslab.lxdpups.config.PortalConfigLoader;
import com.scivicslab.lxdpups.model.ServiceStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Manages tool instances in container mode.
 * Each tool can have multiple instances running on different ports within its port range.
 */
@ApplicationScoped
public class ToolInstanceManager {

    private static final Logger LOG = Logger.getLogger(ToolInstanceManager.class.getName());

    @Inject
    PortalConfigLoader configLoader;

    @Inject
    ProcessManager processManager;

    /**
     * A running tool instance.
     */
    public record ToolInstance(String toolName, String description, String icon, int port,
                               ServiceStatus status, String processName, String uiPath,
                               String memo) {}

    // Tracks which ports are allocated per tool: toolName -> set of ports
    private final ConcurrentHashMap<String, List<Integer>> allocatedPorts = new ConcurrentHashMap<>();

    // Memo per tool instance: "toolName-port" -> memo text
    private final ConcurrentHashMap<String, String> memos = new ConcurrentHashMap<>();

    /**
     * Launch a new instance of a tool, finding the next available port in the range.
     * Returns the port number used, or -1 if no port was available or the tool was not found.
     */
    public int launchTool(String toolName) {
        return launchTool(toolName, null);
    }

    /**
     * Launch a new instance of a tool with an optional working directory override.
     * Used by runtime-based tools (e.g. Docusaurus) where the user selects the project directory.
     */
    public int launchTool(String toolName, String workDir) {
        return launchTool(toolName, workDir, Map.of());
    }

    /**
     * Launch a tool with optional working directory and extra placeholder parameters.
     * Extra params replace {key} placeholders in the tool's args (e.g. {workflow}).
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

        // Create a ManagementService from the tool definition to reuse ProcessManager
        var svc = toManagementService(tool, port, extraParams);
        // Override work-dir if specified (e.g. user selected a Docusaurus project)
        if (workDir != null && !workDir.isEmpty() && svc.getBinary() != null) {
            svc.getBinary().setWorkDir(workDir);
        }
        var instanceName = toolName + "-" + port;
        processManager.startAsync(svc);
        allocatedPorts.computeIfAbsent(toolName, k -> new ArrayList<>()).add(port);
        LOG.info("Launched tool " + toolName + " on port " + port + (workDir != null ? " workDir=" + workDir : ""));
        return port;
    }

    /**
     * Stop a specific tool instance by name and port.
     * Returns true if the stop was successful.
     */
    public boolean stopTool(String toolName, int port) {
        var instanceName = toolName + "-" + port;
        boolean ok = processManager.stop(instanceName, port);
        var ports = allocatedPorts.get(toolName);
        if (ports != null) {
            ports.remove(Integer.valueOf(port));
        }
        memos.remove(instanceName);
        LOG.info("Stopped tool " + toolName + " on port " + port);
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
    public List<ToolInstance> getRunningInstances() {
        var result = new ArrayList<ToolInstance>();
        for (var tool : configLoader.getConfig().getTools()) {
            int portStart = tool.getPortStart();
            int portEnd = tool.getPortEnd();
            for (int port = portStart; port <= portEnd; port++) {
                var instanceName = tool.getName() + "-" + port;
                var status = processManager.getStatus(instanceName, port);
                if (status == ServiceStatus.ACTIVE || status == ServiceStatus.STARTING) {
                    String processName = null;
                    if (status == ServiceStatus.ACTIVE) {
                        processName = processManager.getProcessNameOnPort(port);
                    }
                    var key = tool.getName() + "-" + port;
                    result.add(new ToolInstance(
                            tool.getName(), tool.getDescription(), tool.getIcon(),
                            port, status, processName, tool.getUiPath(),
                            memos.getOrDefault(key, "")));
                }
            }
        }
        return result;
    }

    /**
     * Find the next available port in a tool's port range.
     * Returns -1 if all ports are in use.
     */
    int findAvailablePort(PortalConfig.ToolDefinition tool) {
        return findAvailablePortWith(tool, processManager);
    }

    /**
     * Testable variant that accepts an explicit ProcessManager.
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

    /**
     * Convert a ToolDefinition to a ManagementService so ProcessManager can manage it.
     */
    private PortalConfig.ManagementService toManagementService(PortalConfig.ToolDefinition tool, int port) {
        return toManagementService(tool, port, Map.of());
    }

    /**
     * Convert a ToolDefinition to a ManagementService, replacing placeholders in args.
     * Built-in placeholder: {port}. Extra placeholders from extraParams (e.g. {workflow}).
     */
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
            // Replace {port} and extra placeholders in args
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
