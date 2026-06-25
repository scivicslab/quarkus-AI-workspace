package com.scivicslab.aiworkspace.config;

/**
 * Minimal tool registry entry loaded from ai-workspace-tools.yaml.
 * Contains only the information needed to show a "Download Latest" tile
 * before the tool JAR has been acquired.
 */
public record ToolRegistryEntry(String name, String jarFileName, String githubRepo) {}
