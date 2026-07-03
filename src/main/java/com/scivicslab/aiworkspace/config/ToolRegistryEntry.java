package com.scivicslab.aiworkspace.config;

/**
 * Minimal tool registry entry loaded from ai-workspace-tools.yaml.
 * Contains only the information needed to show a "Download Latest" tile
 * before the tool JAR has been acquired.
 *
 * <p>Library entries ({@code library: true}) are Maven multi-module projects whose
 * artifacts are installed to {@code ~/.m2} by the snapshot build. They produce no
 * runnable uber-jar and are therefore not deployed to {@code ~/works/}.</p>
 */
public record ToolRegistryEntry(String name, String jarFileName, String githubRepo, boolean library) {}
