package com.scivicslab.serviceportal.model;

import java.util.List;

/**
 * Dashboard model — three-section layout.
 *
 * - managementServices: autoStart=true tools (e.g. mcp-gateway), one instance each
 * - activeSessions:     all running/starting tool instances
 * - launchTools:        tools that can be launched with user-provided parameters
 */
public record DashboardModel(
    List<SessionView> managementServices,
    List<SessionView> activeSessions,
    List<ToolView> launchTools
) {}
