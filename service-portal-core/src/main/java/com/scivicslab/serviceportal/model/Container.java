package com.scivicslab.serviceportal.model;

/**
 * Container (Worker Containers).
 */
public record Container(
    String name,                // Container name
    String image,               // Image name (e.g., "lxd-pups/ai-tools")
    String status,              // Status ("Running" / "Stopped")
    String ip,                  // IP address
    String remote               // Remote name (usually "local")
) {}
