package com.scivicslab.serviceportal.model;

/**
 * Tool instance (Running Services).
 */
public record ToolInstance(
    String toolName,            // Tool name
    int port,                   // Port number
    String description,         // Display name
    String icon,                // Icon (emoji)
    ServiceStatusEnum status,   // Status
    String uiPath,              // UI path (e.g., "/")
    String memo                 // User memo
) {}
