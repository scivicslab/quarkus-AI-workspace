package com.scivicslab.aiworkspace.config;

import java.util.List;

/**
 * AI workspace configuration loaded from ai-workspace-jvm.yaml.
 */
public record AiWorkspaceConfig(
    String backend,     // "auto" or "jvm"
    String accessHost,  // hostname used in dashboard URLs (default: "localhost")
    JvmConfig jvm
) {
    /**
     * JVM backend configuration.
     */
    public record JvmConfig(
        List<ToolDefinition> tools
    ) {}

    /**
     * Tool definition.
     * If args is non-null and non-empty, the process is launched as:
     *   java -jar <jar> <args...>
     * Otherwise the default Quarkus launch is used:
     *   java -jar <jar> -Dquarkus.http.port=<port>
     *
     * jvmArgs: raw JVM arguments inserted immediately after "java" and before any -D flags.
     *   Example: ["-Xmx4g", "-Xms1g"]
     */
    public record ToolDefinition(
        String name,
        String jar,
        int port,
        boolean autoStart,
        boolean fixedPort,
        boolean singleInstance,
        java.util.List<String> args,
        java.util.List<String> jvmArgs,   // raw JVM flags (e.g. -Xmx4g) inserted before -D props
        java.util.List<ParamDefinition> params,
        String gatewayMcpProp,  // if set, -D{gatewayMcpProp}={gatewayUrl}/mcp/_all is injected at launch
        String github           // GitHub repo in "owner/repo" format for download-latest feature
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
     * Create default configuration.
     */
    public static AiWorkspaceConfig defaultConfig() {
        return new AiWorkspaceConfig("auto", null, null);
    }
}
