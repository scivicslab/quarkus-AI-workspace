package com.scivicslab.serviceportal.config;

import java.util.List;
import java.util.Map;

/**
 * Service portal configuration loaded from service-portal.yaml.
 */
public record ServicePortalConfig(
    String backend,  // "auto", "jvm", "multi-docker", or "lxd"
    DockerConfig jvm,
    MultiDockerConfig multiDocker,
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
     * If args is non-null and non-empty, the process is launched as:
     *   java -jar <jar> <args...>
     * Otherwise the default Quarkus launch is used:
     *   java -jar <jar> -Dquarkus.http.port=<port>
     */
    public record ToolDefinition(
        String name,
        String jar,
        int port,
        boolean autoStart,
        java.util.List<String> args,
        java.util.List<ParamDefinition> params
    ) {}

    /** A user-configurable parameter shown in the tool launch tile. */
    public record ParamDefinition(
        String key,          // unique key within the tool
        String label,        // display label
        String type,         // "dir" | "select" | "text"
        String defaultVal,   // default value (env-vars expanded at launch)
        String jvmProp,      // if set, passed as -D{jvmProp}={value}
        boolean workingDir,  // if true, sets ProcessBuilder working directory
        int argPos,          // if >= 0, replaces args[argPos]
        java.util.List<ParamOption> options  // for type=select
    ) {}

    /** An option entry for a select-type ParamDefinition. */
    public record ParamOption(String value, String label) {}

    /**
     * Multi-Docker backend configuration (multiple ai-toolkit containers).
     */
    public record MultiDockerConfig(
        String image,           // ai-toolkit Docker image tag
        String vllmEndpoint,    // vLLM server URL (env-var expansion supported)
        String defaultWorkdir   // default working directory for new AI teams
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
        return new ServicePortalConfig("auto", null, null, null);  // jvm=null
    }
}
