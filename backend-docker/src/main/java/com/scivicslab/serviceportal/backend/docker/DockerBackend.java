package com.scivicslab.serviceportal.backend.docker;

import com.scivicslab.serviceportal.config.ServicePortalConfig;
import com.scivicslab.serviceportal.model.*;
import com.scivicslab.serviceportal.spi.ServiceBackend;
import com.scivicslab.serviceportal.spi.ServiceException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Docker backend implementation using process management.
 * Manages Java processes within a Docker container.
 */
public class DockerBackend implements ServiceBackend {

    private static final Logger logger = Logger.getLogger(DockerBackend.class.getName());

    private final Map<String, ProcessSupervisor> supervisors = new ConcurrentHashMap<>();

    @Override
    public void initialize(ServicePortalConfig config) {
        if (config.docker() == null || config.docker().tools() == null) {
            logger.warning("No Docker tools configured");
            return;
        }

        for (ServicePortalConfig.ToolDefinition tool : config.docker().tools()) {
            ProcessSupervisor supervisor = new ProcessSupervisor(tool);
            supervisors.put(tool.name(), supervisor);

            // Auto-start サービスを起動
            if (tool.autoStart()) {
                try {
                    supervisor.start();
                } catch (Exception e) {
                    logger.warning("Failed to auto-start " + tool.name() + ": " + e.getMessage());
                }
            }
        }

        logger.info("Docker backend initialized with " + supervisors.size() + " tools");
    }

    @Override
    public void startService(String serviceId) throws ServiceException {
        ProcessSupervisor supervisor = supervisors.get(serviceId);
        if (supervisor == null) {
            throw new ServiceException("Service not found: " + serviceId);
        }
        supervisor.start();
    }

    @Override
    public void stopService(String serviceId) throws ServiceException {
        ProcessSupervisor supervisor = supervisors.get(serviceId);
        if (supervisor == null) {
            throw new ServiceException("Service not found: " + serviceId);
        }
        supervisor.stop();
    }

    @Override
    public List<ServiceStatus> getServiceStatuses() {
        return supervisors.values().stream()
            .map(ProcessSupervisor::getStatus)
            .toList();
    }

    @Override
    public List<String> getServiceLogs(String serviceId, int lines) {
        ProcessSupervisor supervisor = supervisors.get(serviceId);
        if (supervisor == null) {
            return List.of();
        }
        return supervisor.getRecentLogs(lines);
    }

    @Override
    public String getBackendType() {
        return "docker";
    }

    @Override
    public DashboardModel getDashboardModel() {
        List<ToolInstance> toolInstances = supervisors.values().stream()
            .map(supervisor -> {
                ServiceStatus s = supervisor.getStatus();
                ServiceStatusEnum statusEnum = switch (s.status()) {
                    case RUNNING   -> ServiceStatusEnum.ACTIVE;
                    case STOPPED,
                         STOPPING  -> ServiceStatusEnum.INACTIVE;
                    case STARTING  -> ServiceStatusEnum.STARTING;
                    case ERROR     -> ServiceStatusEnum.FAILED;
                };
                return new ToolInstance(s.id(), s.port(), s.name(), "", statusEnum, "/", "");
            })
            .toList();

        List<ToolDefinition> tools = supervisors.values().stream()
            .map(supervisor -> {
                ServicePortalConfig.ToolDefinition cfg = supervisor.getConfig();
                return new ToolDefinition(cfg.name(), cfg.name(), "", cfg.jar(), cfg.port(), null, cfg.autoStart());
            })
            .toList();

        return new DashboardModel(
            true,        // containerMode
            false,       // hostMode
            "localhost", // myIp
            List.of(),   // managementServices
            toolInstances,
            tools,
            List.of(),   // containers
            new HashMap<>(), // containerProgress
            List.of(),   // hostTools
            ""           // storageInfo
        );
    }
}
