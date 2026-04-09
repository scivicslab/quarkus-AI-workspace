package com.scivicslab.serviceportal.model;

import java.util.List;

/**
 * Container progress information.
 */
public record ContainerProgress(
    String name,                // Container name
    boolean portalOnline,       // Portal online flag
    List<ServiceStatusEnum> services // Service statuses in container
) {}
