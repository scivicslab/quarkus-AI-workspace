package com.scivicslab.aiworkspace.spi;

import com.scivicslab.aiworkspace.model.DashboardModel;
import com.scivicslab.aiworkspace.model.ToolView;
import com.scivicslab.aiworkspace.config.AiWorkspaceConfig;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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
     * Restarts every running instance of the named tool, one at a time, in port order: each is
     * stopped, then started again on the same port with the parameters it was launched with, and
     * the next is not touched until that one is ready. Takes as long as the instances take to start,
     * so it is meant to be called from a background thread.
     *
     * @param progress told one line each time an instance moves to its next step
     * @throws ServiceException if the tool has no running instance, or an instance does not stop,
     *                          free its port, or become ready; instances not yet reached are left running
     */
    void restartAll(String toolName, java.util.function.Consumer<String> progress) throws ServiceException;

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
     * Returns the GitHub repository ("owner/repo") for the named tool, or empty if unknown.
     * Used by the "Download Latest" feature.
     */
    default Optional<String> getGithubRepo(String toolName) { return Optional.empty(); }

    /**
     * Returns the JAR file name (e.g. "quarkus-chat-ui.jar") for the named tool, or empty if unknown.
     * Used by the "Download Latest" feature.
     */
    default Optional<String> getJarFileName(String toolName) { return Optional.empty(); }
}
