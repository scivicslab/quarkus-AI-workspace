package com.scivicslab.serviceportal.backend.docker;

import com.scivicslab.serviceportal.config.ServicePortalConfig;
import com.scivicslab.serviceportal.model.DashboardModel;
import com.scivicslab.serviceportal.model.ParamDefinition;
import com.scivicslab.serviceportal.model.SessionState;
import com.scivicslab.serviceportal.model.ToolView;
import com.scivicslab.serviceportal.spi.ServiceBackend;
import com.scivicslab.serviceportal.spi.ServiceException;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * ServiceBackend implementation for multi-AI-team management.
 *
 * Manages three types of named, multi-instance services:
 *   - AI Toolkit  (ai-toolkit containers, 90 ports each): host 29180, 29270, 29360 ...
 *   - Jupyter Lab (single-port containers):               host 29100, 29101, 29102 ...
 *   - Remote Desktop (single-port containers):            host 29110, 29111, 29112 ...
 *
 * The host portal itself runs on :29080.
 */
@RegisterForReflection
public class MultiDockerBackend implements ServiceBackend {

    private static final Logger logger = Logger.getLogger(MultiDockerBackend.class.getName());

    // AI Toolkit port allocation: 90 ports per team, up to 9 teams
    private static final int TEAM_HOST_BASE    = 29180;
    private static final int CONTAINER_BASE    = 28080;
    private static final int TEAM_PORT_RANGE   = 90;
    private static final int MAX_TEAMS         = 9;

    // Jupyter Lab: up to 10 named instances
    private static final int JUPYTER_BASE      = 29100;
    private static final int JUPYTER_MAX       = 10;
    private static final int JUPYTER_CONTAINER_PORT = 8888;

    // Remote Desktop: up to 10 named instances
    private static final int DESKTOP_BASE      = 29110;
    private static final int DESKTOP_MAX       = 10;
    private static final int DESKTOP_CONTAINER_PORT = 8080;

    /** name → AI team supervisor */
    private final ConcurrentHashMap<String, AiTeamSupervisor> teams    = new ConcurrentHashMap<>();
    /** name → service supervisor (jupyter-lab, remote-desktop) */
    private final ConcurrentHashMap<String, ServiceSupervisor> services = new ConcurrentHashMap<>();

    private ServicePortalConfig config;

    @Override
    public void initialize(ServicePortalConfig config) {
        this.config = config;
        logger.info("MultiDockerBackend initialized. image=" + aiToolkitImage());
    }

    @Override
    public void startService(String toolType, Map<String, String> params) throws ServiceException {
        String name = params.getOrDefault("name", params.getOrDefault("teamName", "default"));
        switch (toolType) {
            case "jupyter-lab"    -> startNamedService(name, "jupyter-lab",    "Jupyter Lab",
                                         JUPYTER_BASE, JUPYTER_MAX, JUPYTER_CONTAINER_PORT,
                                         jupyterImage());
            case "remote-desktop" -> startNamedService(name, "remote-desktop", "Remote Desktop",
                                         DESKTOP_BASE, DESKTOP_MAX, DESKTOP_CONTAINER_PORT,
                                         desktopImage());
            default               -> startAiTeam(name, params);
        }
    }

    @Override
    public void stopService(String name, int port) throws ServiceException {
        ServiceSupervisor svc = services.get(name);
        if (svc != null) { svc.stop(); return; }

        AiTeamSupervisor sup = teams.get(name);
        if (sup == null) {
            sup = teams.values().stream()
                .filter(s -> s.getBasePort() == port)
                .findFirst()
                .orElseThrow(() -> new ServiceException("No service named: " + name));
        }
        sup.stop();
    }

    @Override
    public List<String> getServiceLogs(String name, int port, int lines) {
        AiTeamSupervisor sup = teams.values().stream()
            .filter(s -> s.getBasePort() == port)
            .findFirst().orElse(null);
        if (sup == null) return List.of();
        var log = sup.toSessionView().progressLog();
        return log.stream().skip(Math.max(0, log.size() - lines)).toList();
    }

    @Override
    public DashboardModel getDashboardModel() {
        var aiSessions = teams.values().stream()
            .filter(s -> s.getState() != SessionState.STOPPED)
            .map(AiTeamSupervisor::toSessionView);

        var svcSessions = services.values().stream()
            .filter(s -> s.getState() != SessionState.STOPPED)
            .map(ServiceSupervisor::toSessionView);

        return new DashboardModel(
            List.of(),
            Stream.concat(aiSessions, svcSessions).toList(),
            buildLaunchTools()
        );
    }

    @Override
    public String getBackendType() {
        return "multi-docker";
    }

    // ---------------------------------------------------------------
    // Private: start helpers
    // ---------------------------------------------------------------

    private void startAiTeam(String name, Map<String, String> params) throws ServiceException {
        if (teams.containsKey(name)) {
            AiTeamSupervisor existing = teams.get(name);
            if (existing.getState() != SessionState.STOPPED) {
                throw new ServiceException("AI Toolkit already running: " + name);
            }
            teams.remove(name);
        }
        int slot = allocateTeamSlot();
        int base = TEAM_HOST_BASE + TEAM_PORT_RANGE * slot;
        AiTeamSupervisor sup = new AiTeamSupervisor(name, base, params, aiToolkitImage(), vllmEndpoint());
        teams.put(name, sup);
        sup.start();
        logger.info("Launching AI Toolkit '" + name + "' on port " + base);
    }

    private void startNamedService(String name, String toolType, String displayName,
                                   int portBase, int maxInstances, int containerPort,
                                   String image) throws ServiceException {
        if (services.containsKey(name)) {
            ServiceSupervisor existing = services.get(name);
            if (existing.getState() != SessionState.STOPPED) {
                throw new ServiceException(displayName + " already running: " + name);
            }
            services.remove(name);
        }
        int hostPort = allocateServicePort(toolType, portBase, maxInstances);
        ServiceSupervisor sup = new ServiceSupervisor(name, toolType, displayName,
                                                       hostPort, containerPort, image);
        services.put(name, sup);
        sup.start();
        logger.info("Launching " + displayName + " '" + name + "' on port " + hostPort);
    }

    // ---------------------------------------------------------------
    // Private: port allocation
    // ---------------------------------------------------------------

    private int allocateTeamSlot() throws ServiceException {
        for (int slot = 0; slot < MAX_TEAMS; slot++) {
            int base = TEAM_HOST_BASE + TEAM_PORT_RANGE * slot;
            boolean inUse = teams.values().stream()
                .anyMatch(s -> s.getBasePort() == base && s.getState() != SessionState.STOPPED);
            if (!inUse) return slot;
        }
        throw new ServiceException("Maximum AI Toolkit teams reached (" + MAX_TEAMS + ")");
    }

    private int allocateServicePort(String toolType, int portBase, int maxInstances)
            throws ServiceException {
        for (int i = 0; i < maxInstances; i++) {
            int port = portBase + i;
            boolean inUse = services.values().stream()
                .anyMatch(s -> s.getToolType().equals(toolType)
                            && s.getHostPort() == port
                            && s.getState() != SessionState.STOPPED);
            if (!inUse) return port;
        }
        throw new ServiceException("Maximum instances reached for " + toolType
                                   + " (" + maxInstances + ")");
    }

    // ---------------------------------------------------------------
    // Private: image and config helpers
    // ---------------------------------------------------------------

    private String aiToolkitImage() {
        if (config.multiDocker() != null && config.multiDocker().image() != null) {
            return config.multiDocker().image();
        }
        return "scivicslab/ai-toolkit:latest";
    }

    private String jupyterImage() {
        return "192.168.5.23:32000/jupyter-lab:4.5.5-2602281600";
    }

    private String desktopImage() {
        return "192.168.5.23:32000/guacamole-desktop:3.1.1-2602281555";
    }

    private String vllmEndpoint() {
        if (config.multiDocker() != null && config.multiDocker().vllmEndpoint() != null) {
            return ProcessSupervisor.expandEnvVars(config.multiDocker().vllmEndpoint());
        }
        String env = System.getenv("VLLM_ENDPOINT");
        return env != null ? env : "http://host.docker.internal:8000";
    }

    private List<ToolView> buildLaunchTools() {
        List<ParamDefinition> nameParam = List.of(
            new ParamDefinition("name", "Name", "text", "", List.of())
        );
        return List.of(
            new ToolView("ai-toolkit",     "AI Toolkit",     "", nameParam),
            new ToolView("jupyter-lab",    "Jupyter Lab",    "", nameParam),
            new ToolView("remote-desktop", "Remote Desktop", "", nameParam)
        );
    }
}
