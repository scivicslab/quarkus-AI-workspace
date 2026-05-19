package com.scivicslab.aiworkspace.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum SessionState {
    STARTING,
    READY,
    FAILED,
    STOPPED,
    /** Singleton-only: external MCP Gateway URL is in use; the team-dedicated subprocess is not running. */
    EXTERNAL;

    public boolean isStarting() { return this == STARTING; }
    public boolean isFailed()   { return this == FAILED; }
    public boolean isStopped()  { return this == STOPPED; }
    public boolean isExternal() { return this == EXTERNAL; }
}
