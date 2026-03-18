package com.scivicslab.lxdpups.model;

/**
 * A service running on the host.
 */
public record HostService(
        String name,
        String unit,
        int port,
        String description,
        String uiUrl,
        ServiceStatus status,
        String processName
) {}
