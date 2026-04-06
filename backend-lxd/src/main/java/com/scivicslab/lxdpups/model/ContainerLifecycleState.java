package com.scivicslab.lxdpups.model;

/**
 * Unified 6-state lifecycle model for both Management Services and Worker Containers.
 *
 * State transitions:
 *   CREATING -> STARTING -> READY -> STOPPING -> STOPPED
 *   CREATING -> FAILED
 *   STARTING -> FAILED
 *   READY    -> FAILED
 *   FAILED   -> STOPPING, STOPPED
 */
public enum ContainerLifecycleState {
    CREATING,
    STARTING,
    READY,
    STOPPING,
    STOPPED,
    FAILED;

    /**
     * Returns true if this state is terminal (no further transitions possible).
     */
    public boolean isTerminal() {
        return this == STOPPED;
    }

    /**
     * Returns true if this state indicates the service is operational.
     */
    public boolean isOperational() {
        return this == READY;
    }

    /**
     * Returns true if the service entity exists (container running, process alive).
     */
    public boolean hasEntity() {
        return this == CREATING || this == STARTING || this == READY || this == FAILED;
    }
}
