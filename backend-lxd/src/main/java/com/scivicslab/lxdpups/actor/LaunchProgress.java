package com.scivicslab.lxdpups.actor;

import com.scivicslab.lxdpups.model.ServiceProgress;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Mutable progress state for a container launch operation.
 * Written by LaunchWorkerActor, read by ContainerSupervisorActor.
 */
public class LaunchProgress {

    private final String name;
    private final String template;
    private final Instant startedAt;
    private volatile String phase = "queued";
    private final List<String> messages = new ArrayList<>();
    private volatile boolean done = false;
    private volatile boolean success = false;

    public LaunchProgress(String name, String template) {
        this.name = name;
        this.template = template;
        this.startedAt = Instant.now();
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

    public boolean isDone() {
        return done;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getName() {
        return name;
    }

    public String getTemplate() {
        return template;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public ServiceProgress toServiceProgress() {
        return new ServiceProgress(name, phase, List.copyOf(messages), done, success);
    }
}
