package com.scivicslab.aiworkspace.version;

import com.scivicslab.aiworkspace.config.ToolRegistryEntry;
import com.scivicslab.aiworkspace.config.ToolRegistryLoader;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Holds what GitHub said the last time it was asked, and asks again on request.
 *
 * <p>GitHub is not asked when a screen is drawn. Its REST API allows sixty requests an hour from
 * one address without authentication, and the registry has ten entries, so drawing the Catalog
 * screen six times would use them all up. It is asked when the {@code Refresh versions} button is
 * pressed, and what it said is kept until the button is pressed again
 * ({@code ToolVersions_260907_oo01}).
 *
 * <p>What is kept lives in this object and nowhere else. Restarting AI-workspace empties it, and
 * the two lines it feeds are blank until the button is pressed.
 */
@ApplicationScoped
public class ToolVersionStore {

    private static final Logger logger = Logger.getLogger(ToolVersionStore.class.getName());

    @Inject
    GitHubVersionFetcher fetcher;

    private final Map<String, RemoteVersions> byTool = new ConcurrentHashMap<>();
    private volatile Instant fetchedAt;
    private volatile List<String> failedTools = List.of();

    /** What is known for one tool. Never null; both versions are empty when nothing is known. */
    public RemoteVersions get(String toolName) {
        return byTool.getOrDefault(toolName, RemoteVersions.none());
    }

    /** When the last refresh ran, or null when it has never run. */
    public Instant fetchedAt() {
        return fetchedAt;
    }

    /** The tools whose repository could not be read in the last refresh. */
    public List<String> failedTools() {
        return failedTools;
    }

    /**
     * Asks GitHub about every registry entry that names a repository.
     *
     * <p>Each entry is answered on its own: one repository that cannot be read leaves the others'
     * versions replaced, and leaves that one entry's previous versions in place.
     *
     * @return the tools whose repository could not be read
     */
    public synchronized List<String> refresh() {
        List<String> failed = new ArrayList<>();
        for (ToolRegistryEntry entry : ToolRegistryLoader.load()) {
            if (entry.githubRepo() == null || entry.githubRepo().isBlank()) continue;
            try {
                byTool.put(entry.name(), fetcher.fetch(entry.githubRepo()));
            } catch (Exception e) {
                failed.add(entry.name());
                logger.warning("Could not read versions for " + entry.name()
                        + " (" + entry.githubRepo() + "): " + e.getMessage());
            }
        }
        fetchedAt = Instant.now();
        failedTools = List.copyOf(failed);
        return failedTools;
    }

    /** Every tool's versions, keyed by tool name. For the REST response. */
    public Map<String, RemoteVersions> all() {
        return new LinkedHashMap<>(byTool);
    }
}
