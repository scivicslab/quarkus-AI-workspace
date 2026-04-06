package com.scivicslab.serviceportal.model;

/**
 * Service status information.
 */
public record ServiceStatus(
    String id,              // Service identifier
    String name,            // Display name
    int port,               // Port number
    Status status,          // RUNNING / STOPPED
    boolean autoStart,      // Auto-start on portal startup
    String details          // Backend-specific details (PID, unit name, etc.)
) {
    public enum Status {
        RUNNING,
        STOPPED,
        STARTING,
        STOPPING,
        ERROR
    }
}
