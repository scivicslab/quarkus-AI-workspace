package com.scivicslab.serviceportal.model;

/**
 * Host service (Management Services).
 */
public record HostService(
    String name,                // Service name (e.g., "mcp-gateway")
    String unit,                // systemd unit name (e.g., "mcp-gateway.service")
    int port,                   // Port number
    String description,         // Display name
    String uiUrl,               // UI URL (e.g., "http://localhost:8888")
    ServiceStatusEnum status,   // Status (ACTIVE, INACTIVE, STARTING, FAILED, UNKNOWN)
    String processName          // Process name (container mode)
) {}
