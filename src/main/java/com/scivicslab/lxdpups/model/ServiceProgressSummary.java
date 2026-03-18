package com.scivicslab.lxdpups.model;

/**
 * Summary of a single service's progress inside a container portal.
 */
public record ServiceProgressSummary(
        String name,
        String description,
        int port,
        String status,
        String phase,
        boolean done,
        boolean success
) {}
