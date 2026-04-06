package com.scivicslab.lxdpups.model;

import java.util.List;

/**
 * Tracks real-time progress of a management service start operation.
 */
public record ServiceProgress(
        String name,
        String phase,
        List<String> messages,
        boolean done,
        boolean success
) {
    public static ServiceProgress idle(String name) {
        return new ServiceProgress(name, "idle", List.of(), true, true);
    }
}
