package com.scivicslab.lxdpups.model;

import java.util.List;

/**
 * Response from a container portal's /api/progress endpoint.
 * Aggregates the status of all services running inside the container.
 */
public record ContainerProgressResponse(
        String title,
        List<ServiceProgressSummary> services,
        boolean portalOnline
) {}
