package com.scivicslab.aiworkspace.spi;

import com.scivicslab.aiworkspace.model.DashboardModel;
import com.scivicslab.aiworkspace.config.AiWorkspaceConfig;

import java.util.List;
import java.util.Map;

/**
 * Service management backend abstraction.
 *
 * Current implementation: JvmBackend — manages java -jar child processes
 * for the tools of a single AI team.
 */
public interface ServiceBackend {

    /** Initialize backend with configuration. Called once at application startup. */
    void initialize(AiWorkspaceConfig config);

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

    /** Returns the backend type string (e.g. "jvm"). */
    String getBackendType();

    /**
     * Switch the team's MCP Gateway to an external URL.
     * Stops the team-dedicated subprocess if it is running.
     * Default implementation: throws ServiceException (backend does not support).
     */
    default void useExternalGateway(String url) throws ServiceException {
        throw new ServiceException("Backend does not support external MCP Gateway");
    }

    /**
     * Switch the team's MCP Gateway back to the team-dedicated subprocess.
     * Starts the subprocess if it is not running.
     * Default implementation: throws ServiceException (backend does not support).
     */
    default void useInternalGateway() throws ServiceException {
        throw new ServiceException("Backend does not support internal MCP Gateway");
    }
}
