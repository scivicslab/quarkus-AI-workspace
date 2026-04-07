package com.scivicslab.serviceportal.spi;

import com.scivicslab.serviceportal.model.DashboardModel;
import com.scivicslab.serviceportal.config.ServicePortalConfig;

import java.util.List;
import java.util.Map;

/**
 * Service management backend abstraction.
 *
 * Implementations:
 * - DockerBackend: manages java processes inside a Docker/k8s container
 * - LxdBackend:    manages LXC containers and systemd services on an LXD host
 */
public interface ServiceBackend {

    /** Initialize backend with configuration. Called once at application startup. */
    void initialize(ServicePortalConfig config);

    /**
     * Launch a new instance of the named tool with the given parameters.
     * The same tool can be launched multiple times with different parameters;
     * each instance gets a unique port.
     */
    void startService(String toolName, Map<String, String> params) throws ServiceException;

    /**
     * Stop the instance of the named tool running on the given port.
     */
    void stopService(String toolName, int port) throws ServiceException;

    /**
     * Return recent log lines for a specific instance.
     */
    List<String> getServiceLogs(String toolName, int port, int lines);

    /**
     * Return the dashboard model for UI rendering.
     * This is the primary read path for both the HTML dashboard and the /api/status endpoint.
     */
    DashboardModel getDashboardModel();

    /** Update the memo for a specific instance. No-op if instance not found. */
    default void updateMemo(String toolName, int port, String memo) {}

    /** Returns "docker" or "lxd". Used by BackendLoader. */
    String getBackendType();
}
