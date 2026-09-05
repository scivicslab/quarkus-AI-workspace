package com.scivicslab.aiworkspace.model;

import java.util.List;

/**
 * Dashboard model — three-section layout.
 *
 * - managementServices: autoStart=true tools
 * - activeSessions:     all running/starting tool instances
 * - launchTools:        tools that can be launched with user-provided parameters
 */
public record DashboardModel(
    List<SessionView> managementServices,
    List<SessionView> activeSessions,
    List<ToolView> launchTools
) {
}
