package com.scivicslab.lxdpups.actor;

import com.scivicslab.lxdpups.model.ServiceProgress;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable progress state for a service start operation.
 * Written by ProcessWorkerActor, read by ProcessSupervisorActor via polling.
 * volatile fields allow safe cross-thread reads (worker writes, supervisor reads).
 */
public class ProcessProgress {

    private final String name;
    volatile String phase = "starting";
    final List<String> messages = new ArrayList<>();
    volatile boolean done = false;
    volatile boolean success = false;
    // Index of the mutable download progress line (-1 = none)
    volatile int downloadLineIndex = -1;

    public ProcessProgress(String name) {
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

    /**
     * Update the download progress line in-place (replaces last progress line).
     * curl progress-bar output is continuously updated on the same line.
     */
    public void updateDownloadLine(String line) {
        if (downloadLineIndex < 0) {
            downloadLineIndex = messages.size();
            messages.add(line);
        } else {
            messages.set(downloadLineIndex, line);
        }
    }

    public ServiceProgress toServiceProgress() {
        return new ServiceProgress(name, phase, List.copyOf(messages), done, success);
    }
}
