package com.scivicslab.serviceportal.spi;

import com.scivicslab.serviceportal.model.ServiceStatus;
import com.scivicslab.serviceportal.model.DashboardModel;
import com.scivicslab.serviceportal.config.ServicePortalConfig;

import java.util.List;

/**
 * Service management backend abstraction.
 *
 * Implementations:
 * - DockerBackend: manages processes in a Docker container
 * - LxdBackend: manages LXC containers and systemd services
 */
public interface ServiceBackend {

    /**
     * Initialize backend with configuration.
     * Called once at application startup.
     *
     * @param config Configuration loaded from service-portal.yaml
     */
    void initialize(ServicePortalConfig config);

    /**
     * Start a service.
     *
     * @param serviceId Service identifier (e.g., "quarkus-chat-ui")
     * @throws ServiceException if service does not exist or start fails
     */
    void startService(String serviceId) throws ServiceException;

    /**
     * Stop a service.
     *
     * @param serviceId Service identifier
     * @throws ServiceException if service does not exist or stop fails
     */
    void stopService(String serviceId) throws ServiceException;

    /**
     * Get current status of all services.
     *
     * @return List of service statuses
     */
    List<ServiceStatus> getServiceStatuses();

    /**
     * Get logs for a service.
     *
     * @param serviceId Service identifier
     * @param lines Number of lines to retrieve (default: 100)
     * @return Log lines
     */
    List<String> getServiceLogs(String serviceId, int lines);

    /**
     * Get backend type.
     *
     * @return "docker" or "lxd"
     */
    String getBackendType();

    /**
     * Get dashboard model for UI rendering.
     *
     * @return Dashboard model with all data
     */
    DashboardModel getDashboardModel();
}
