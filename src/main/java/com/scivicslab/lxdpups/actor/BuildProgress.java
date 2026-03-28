package com.scivicslab.lxdpups.actor;

import com.scivicslab.lxdpups.model.ServiceProgress;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable progress state for a tool build operation.
 * Written by BuildWorkerActor, read by BuildSupervisorActor via polling.
 * volatile fields allow safe cross-thread reads (worker writes, supervisor reads).
 */
public class BuildProgress {

    private final String name;
    volatile String phase = "queued";
    final List<String> messages = new ArrayList<>();
    volatile boolean done = false;
    volatile boolean success = false;

    public BuildProgress(String name) {
        this.name = name;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public void addMessage(String msg) {
        messages.add(msg);
    }

    public void complete(boolean ok) {
        this.done = true;
        this.success = ok;
    }

    public ServiceProgress toServiceProgress() {
        return new ServiceProgress(name, phase, List.copyOf(messages), done, success);
    }
}
