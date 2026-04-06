package com.scivicslab.serviceportal.model;

/**
 * Host tool (Tools in host mode).
 */
public record HostTool(
    String name,                // Tool name
    String description,         // Description
    String icon,                // Icon (emoji)
    String url                  // External URL
) {}
