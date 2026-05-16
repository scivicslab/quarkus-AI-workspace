package com.scivicslab.serviceportal.model;

import java.util.List;

/**
 * Dashboard model — three-section layout plus MCP Gateway status.
 *
 * - managementServices: autoStart=true tools (e.g. mcp-gateway), empty in EXTERNAL mode
 * - activeSessions:     all running/starting tool instances
 * - launchTools:        tools that can be launched with user-provided parameters
 * - mcpGateway:         current MCP Gateway mode and active URL (Internal / External)
 */
public record DashboardModel(
    List<SessionView> managementServices,
    List<SessionView> activeSessions,
    List<ToolView> launchTools,
    McpGatewayStatus mcpGateway
) {
    /** Backwards-compatible constructor: defaults mcpGateway to null. */
    public DashboardModel(List<SessionView> managementServices,
                          List<SessionView> activeSessions,
                          List<ToolView> launchTools) {
        this(managementServices, activeSessions, launchTools, null);
    }
}
