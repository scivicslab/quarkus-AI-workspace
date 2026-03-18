package com.scivicslab.lxdpups.model;

import java.util.List;

/**
 * Aggregate status of all management services and worker containers.
 */
public record PortalStatus(
        List<HostService> managementServices,
        List<ContainerInfo> containers
) {}
