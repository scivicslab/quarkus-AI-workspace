package com.scivicslab.aiworkspace.config;

import java.util.List;

/**
 * A fully self-contained tool registry entry loaded from ai-workspace-tools.yaml.
 *
 * <p>This is the single source of truth for how AI workspace launches and displays a tool:
 * name, jar, GitHub repo, launch port/args/jvmArgs, behavioural flags, and the dashboard
 * form ({@code params}). Tools are plain standalone programs and carry no AI-workspace-specific
 * code; all of their launch metadata lives here.</p>
 *
 * <p>Library entries ({@code library: true}) are Maven multi-module projects whose artifacts are
 * installed to {@code ~/.m2} by the snapshot build. They produce no runnable uber-jar and are
 * therefore not deployed to {@code ~/works/}; only {@code name}/{@code githubRepo}/{@code library}
 * are meaningful for them.</p>
 */
public record ToolRegistryEntry(
    String name,
    String jarFileName,
    String githubRepo,
    boolean library,
    int defaultPort,
    boolean autoStart,
    boolean fixedPort,
    boolean singleInstance,
    List<String> args,
    List<String> jvmArgs,
    List<AiWorkspaceConfig.ParamDefinition> params,
    String gatewayMcpProp,
    List<String> dependsOn,
    List<String> modules
) {}
