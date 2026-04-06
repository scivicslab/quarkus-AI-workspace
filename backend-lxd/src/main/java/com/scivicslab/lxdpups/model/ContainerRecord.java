package com.scivicslab.lxdpups.model;

import java.time.Instant;

/**
 * Immutable record tracking per-container lifecycle state with timestamps.
 *
 * Uses copy-on-write methods (withState, withIp, withActivity) to produce
 * new instances. The original is never mutated.
 */
public final class ContainerRecord {

    private final String name;
    private final String image;
    private final String remote;
    private final String ip;
    private final ContainerLifecycleState state;
    private final Instant createdAt;
    private final Instant lastActivityAt;
    private final Instant failedAt;
    private final String failureReason;

    public ContainerRecord(String name, String image, String remote, ContainerLifecycleState state) {
        this(name, image, remote, null, state, Instant.now(), Instant.now(), null, null);
    }

    private ContainerRecord(String name, String image, String remote, String ip,
                            ContainerLifecycleState state, Instant createdAt,
                            Instant lastActivityAt, Instant failedAt, String failureReason) {
        this.name = name;
        this.image = image;
        this.remote = remote;
        this.ip = ip;
        this.state = state;
        this.createdAt = createdAt;
        this.lastActivityAt = lastActivityAt;
        this.failedAt = failedAt;
        this.failureReason = failureReason;
    }

    /**
     * Transition to a new state with validation.
     * If transitioning to FAILED, use withFailed() instead to include a reason.
     */
    public ContainerRecord withState(ContainerLifecycleState newState) {
        ContainerStateMachine.transition(state, newState);
        Instant newFailedAt = (newState == ContainerLifecycleState.FAILED) ? Instant.now() : failedAt;
        return new ContainerRecord(name, image, remote, ip, newState,
                createdAt, Instant.now(), newFailedAt, failureReason);
    }

    /**
     * Transition to FAILED state with a reason.
     */
    public ContainerRecord withFailed(String reason) {
        ContainerStateMachine.transition(state, ContainerLifecycleState.FAILED);
        return new ContainerRecord(name, image, remote, ip, ContainerLifecycleState.FAILED,
                createdAt, Instant.now(), Instant.now(), reason);
    }

    /**
     * Update the IP address (typically when container obtains an IP during launch).
     */
    public ContainerRecord withIp(String newIp) {
        return new ContainerRecord(name, image, remote, newIp, state,
                createdAt, Instant.now(), failedAt, failureReason);
    }

    /**
     * Record user activity (dashboard access, service use) to reset idle timer.
     */
    public ContainerRecord withActivity() {
        return new ContainerRecord(name, image, remote, ip, state,
                createdAt, Instant.now(), failedAt, failureReason);
    }

    public String getName() { return name; }
    public String getImage() { return image; }
    public String getRemote() { return remote; }
    public String getIp() { return ip; }
    public ContainerLifecycleState getState() { return state; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastActivityAt() { return lastActivityAt; }
    public Instant getFailedAt() { return failedAt; }
    public String getFailureReason() { return failureReason; }

    @Override
    public String toString() {
        return "ContainerRecord{name='" + name + "', state=" + state
                + ", image='" + image + "', ip='" + ip + "'}";
    }
}
