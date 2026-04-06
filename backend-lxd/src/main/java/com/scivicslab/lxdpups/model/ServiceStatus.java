package com.scivicslab.lxdpups.model;

/**
 * Unified service status for both Management Services and container-internal services.
 * Aligned with the 6-state ContainerLifecycleState model.
 */
public enum ServiceStatus {
    CREATING,
    STARTING,
    READY,
    STOPPING,
    STOPPED,
    FAILED;

    /**
     * Maps systemd ActiveState strings to ServiceStatus.
     */
    public static ServiceStatus fromSystemdState(String activeState) {
        if (activeState == null) {
            return STOPPED;
        }
        return switch (activeState) {
            case "active" -> READY;
            case "inactive" -> STOPPED;
            case "failed" -> FAILED;
            case "activating" -> STARTING;
            case "deactivating" -> STOPPING;
            default -> STOPPED;
        };
    }
}
