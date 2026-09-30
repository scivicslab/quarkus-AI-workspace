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
    List<String> dependsOn,
    List<String> modules,
    /** Link names of the jars that accompany the tool's own jar — plugin jars built from the
     *  same repository — which Build Snapshot and Download Latest place in ~/works alongside it
     *  ({@code CompanionJars_260912_oo01}). Empty for a tool with one jar. */
    List<String> companionJars,
    /** Base name of the jar the build produces ({@code <artifact>-<version>.jar}); null when it is
     *  the link name without {@code .jar}. Two entries may link one repository's jar under different
     *  names ({@code TwoTilesOneRepository_260930_oo01}). */
    String artifact,
    /** The branch Build Snapshot checks out and Latest SNAPSHOT reads; null for the default branch. */
    String branch
) {
    /** The base name the build's jar is found by: {@code artifact}, else the link name without {@code .jar}. */
    public String artifactBase() {
        if (artifact != null && !artifact.isBlank()) return artifact;
        if (jarFileName == null) return null;
        return jarFileName.endsWith(".jar")
            ? jarFileName.substring(0, jarFileName.length() - ".jar".length())
            : jarFileName;
    }
}
