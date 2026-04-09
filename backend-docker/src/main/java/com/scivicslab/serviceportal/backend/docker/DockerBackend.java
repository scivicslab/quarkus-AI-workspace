package com.scivicslab.serviceportal.backend.docker;

import com.scivicslab.serviceportal.config.ServicePortalConfig;
import com.scivicslab.serviceportal.model.DashboardModel;
import com.scivicslab.serviceportal.model.ParamDefinition;
import com.scivicslab.serviceportal.model.SessionState;
import com.scivicslab.serviceportal.model.SessionView;
import com.scivicslab.serviceportal.model.ToolView;
import com.scivicslab.serviceportal.spi.ServiceBackend;
import com.scivicslab.serviceportal.spi.ServiceException;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * ServiceBackend implementation for Docker/k8s environments.
 * Manages java -jar child processes inside the container.
 *
 * Multiple instances of the same tool can run simultaneously on different ports.
 * Port allocation: base port + number of existing active instances for that tool.
 */
@RegisterForReflection
public class DockerBackend implements ServiceBackend {

    private static final Logger logger = Logger.getLogger(DockerBackend.class.getName());

    /** toolName -> list of instances (may have multiple per tool) */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<ProcessSupervisor>> instances
        = new ConcurrentHashMap<>();

    private ServicePortalConfig config;

    @Override
    public void initialize(ServicePortalConfig config) {
        this.config = config;
        if (config.jvm() == null) return;

        for (var tool : config.jvm().tools()) {
            instances.put(tool.name(), new CopyOnWriteArrayList<>());
            if (tool.autoStart()) {
                try {
                    startService(tool.name(), Map.of());
                } catch (ServiceException e) {
                    logger.severe("Failed to auto-start " + tool.name() + ": " + e.getMessage());
                }
            }
        }
        logger.info("Docker backend initialized");
    }

    @Override
    public void startService(String toolName, Map<String, String> params) throws ServiceException {
        ServicePortalConfig.ToolDefinition def = findTool(toolName);
        CopyOnWriteArrayList<ProcessSupervisor> list =
            instances.computeIfAbsent(toolName, k -> new CopyOnWriteArrayList<>());

        // Clean up stopped instances before calculating port offset
        list.removeIf(s -> s.getState() == SessionState.STOPPED);

        int port = def.port() + list.size();
        ProcessSupervisor supervisor = new ProcessSupervisor(def, port, params);
        list.add(supervisor);
        supervisor.start();
    }

    @Override
    public void stopService(String toolName, int port) throws ServiceException {
        List<ProcessSupervisor> list = instances.getOrDefault(toolName, new CopyOnWriteArrayList<>());
        ProcessSupervisor target = list.stream()
            .filter(s -> s.getPort() == port)
            .findFirst()
            .orElseThrow(() -> new ServiceException("No instance of " + toolName + " on port " + port));
        target.stop();
    }

    @Override
    public List<String> getServiceLogs(String toolName, int port, int lines) {
        return instances.getOrDefault(toolName, new CopyOnWriteArrayList<>()).stream()
            .filter(s -> s.getPort() == port)
            .findFirst()
            .map(s -> s.getRecentLogs(lines))
            .orElse(List.of());
    }

    @Override
    public DashboardModel getDashboardModel() {
        List<SessionView> managementServices = new ArrayList<>();
        List<SessionView> activeSessions = new ArrayList<>();
        List<ToolView> launchTools = new ArrayList<>();

        if (config.jvm() == null) {
            return new DashboardModel(managementServices, activeSessions, launchTools);
        }

        for (var tool : config.jvm().tools()) {
            CopyOnWriteArrayList<ProcessSupervisor> list = instances.getOrDefault(tool.name(), new CopyOnWriteArrayList<>());

            if (tool.autoStart()) {
                // Management service section
                if (list.isEmpty()) {
                    managementServices.add(stoppedView(tool));
                } else {
                    for (var s : list) managementServices.add(s.toSessionView());
                }
            } else {
                // Active sessions section: non-stopped instances
                for (var s : list) {
                    if (s.getState() != SessionState.STOPPED) {
                        activeSessions.add(s.toSessionView());
                    }
                }
                // Launch tools section: always show tile
                launchTools.add(toToolView(tool));
            }
        }

        return new DashboardModel(managementServices, activeSessions, launchTools);
    }

    @Override
    public void updateMemo(String toolName, int port, String memo) {
        instances.getOrDefault(toolName, new CopyOnWriteArrayList<>()).stream()
            .filter(s -> s.getPort() == port)
            .findFirst()
            .ifPresent(s -> s.setMemo(memo));
    }

    @Override
    public String getBackendType() {
        return "jvm";
    }

    // ---------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------

    private ServicePortalConfig.ToolDefinition findTool(String name) throws ServiceException {
        if (config.jvm() == null) throw new ServiceException("No docker config");
        return config.jvm().tools().stream()
            .filter(t -> t.name().equals(name))
            .findFirst()
            .orElseThrow(() -> new ServiceException("Tool not found: " + name));
    }

    private SessionView stoppedView(ServicePortalConfig.ToolDefinition tool) {
        return new SessionView(
            tool.name(), tool.port(), tool.name(), "",
            SessionState.STOPPED, null, Map.of(), "", List.of()
        );
    }

    private ToolView toToolView(ServicePortalConfig.ToolDefinition tool) {
        List<ParamDefinition> params = new ArrayList<>();
        if (tool.params() != null) {
            for (var p : tool.params()) {
                List<ParamDefinition.ParamOption> options = p.options() == null ? List.of()
                    : p.options().stream()
                        .map(o -> new ParamDefinition.ParamOption(o.value(), o.label()))
                        .toList();
                params.add(new ParamDefinition(
                    p.key(), p.label(), p.type(),
                    ProcessSupervisor.expandEnvVars(p.defaultVal()),
                    options
                ));
            }
        }
        return new ToolView(tool.name(), tool.name(), "", params);
    }
}
