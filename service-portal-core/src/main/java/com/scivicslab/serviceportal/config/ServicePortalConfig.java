package com.scivicslab.serviceportal.config;

import java.util.List;
import java.util.Map;

/**
 * Service portal configuration loaded from service-portal.yaml.
 */
public record ServicePortalConfig(
    String backend,  // "auto", "docker", or "lxd"
    DockerConfig docker,
    LxdConfig lxd
) {
    /**
     * Docker backend configuration.
     */
    public record DockerConfig(
        List<ToolDefinition> tools
    ) {}

    /**
     * Tool definition for Docker backend.
     */
    public record ToolDefinition(
        String name,
        String jar,
        int port,
        boolean autoStart
    ) {}

    /**
     * LXD backend configuration.
     */
    public record LxdConfig(
        List<ManagementService> management,
        List<ContainerConfig> containers
    ) {}

    /**
     * Management service (systemd) configuration.
     */
    public record ManagementService(
        String unit,
        int port
    ) {}

    /**
     * Container configuration.
     */
    public record ContainerConfig(
        String name,
        String template
    ) {}

    /**
     * Create default configuration.
     */
    public static ServicePortalConfig defaultConfig() {
        return new ServicePortalConfig("auto", null, null);
    }
}
