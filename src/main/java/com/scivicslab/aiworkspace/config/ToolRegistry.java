package com.scivicslab.aiworkspace.config;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

/**
 * Application-scoped holder for the tool registry loaded from
 * {@code ai-workspace-tools.yaml}. Provides lookup methods used by the REST layer
 * (e.g. build-snapshot) that need github repo and library-flag info for ALL
 * registry entries, including library entries that are never added to the
 * JvmBackend's acquired-tool list.
 */
@ApplicationScoped
public class ToolRegistry {

    private List<ToolRegistryEntry> entries = List.of();

    @PostConstruct
    void init() {
        this.entries = ToolRegistryLoader.load();
    }

    public Optional<String> getGithubRepo(String name) {
        return entries.stream()
                .filter(e -> e.name().equals(name))
                .map(ToolRegistryEntry::githubRepo)
                .filter(g -> g != null && !g.isBlank())
                .findFirst();
    }

    public Optional<String> getJarFileName(String name) {
        return entries.stream()
                .filter(e -> e.name().equals(name))
                .map(ToolRegistryEntry::jarFileName)
                .filter(j -> j != null && !j.isBlank())
                .findFirst();
    }

    /** Returns true when the named entry is a library (installs to ~/.m2, no jar deployed). */
    /** @return the link names of the jars that accompany the tool's jar; empty if none */
    public List<String> getCompanionJars(String name) {
        return entries.stream()
                .filter(e -> e.name().equals(name))
                .map(ToolRegistryEntry::companionJars)
                .filter(c -> c != null)
                .findFirst()
                .orElse(List.of());
    }

    public boolean isLibrary(String name) {
        return entries.stream()
                .filter(e -> e.name().equals(name))
                .anyMatch(ToolRegistryEntry::library);
    }
}
