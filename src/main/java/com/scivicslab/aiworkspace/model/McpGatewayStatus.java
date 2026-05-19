package com.scivicslab.aiworkspace.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * MCP Gateway mode and active URL for the dashboard.
 *
 * mode values:
 *   "INTERNAL_STARTING"  — team-dedicated subprocess is starting
 *   "INTERNAL_READY"     — team-dedicated subprocess is running
 *   "INTERNAL_FAILED"    — team-dedicated subprocess failed
 *   "INTERNAL_STOPPED"   — neither subprocess nor external URL is in use
 *   "EXTERNAL"           — external MCP Gateway URL is in use
 *
 * activeUrl is the URL currently propagated to child processes as MCP_GATEWAY_URL,
 * or null when no URL is available.
 */
@RegisterForReflection
public record McpGatewayStatus(String mode, String activeUrl) {}
