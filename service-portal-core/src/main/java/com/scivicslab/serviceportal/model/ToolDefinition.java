package com.scivicslab.serviceportal.model;

/**
 * Tool definition (Available Tools).
 */
public record ToolDefinition(
    String name,                // Tool name
    String description,         // Display name
    String icon,                // Icon
    String jar,                 // JAR file path
    int port,                   // Default port
    String staticUrl,           // Static URL (if any)
    boolean autoStart,          // Auto-start flag
    java.util.List<ParamDefinition> params  // User-configurable launch params
) {}
