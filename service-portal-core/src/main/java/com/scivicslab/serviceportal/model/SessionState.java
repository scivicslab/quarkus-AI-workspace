package com.scivicslab.serviceportal.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum SessionState {
    STARTING,
    READY,
    FAILED,
    STOPPED;

    public boolean isStarting() { return this == STARTING; }
    public boolean isFailed()   { return this == FAILED; }
    public boolean isStopped()  { return this == STOPPED; }
}
