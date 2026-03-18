package com.scivicslab.lxdpups.model;

import java.util.List;

/**
 * An LXC worker container and its services.
 */
public record ContainerInfo(
        String name,
        String status,
        String remote,
        String ip,
        String image,
        String memo,
        List<ContainerService> services
) {

    public record ContainerService(
            String name,
            String unit,
            int port,
            String title,
            ServiceStatus status,
            String uiUrl
    ) {}
}
