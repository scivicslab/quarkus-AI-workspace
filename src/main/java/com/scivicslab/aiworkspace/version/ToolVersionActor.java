package com.scivicslab.aiworkspace.version;

import com.scivicslab.aiworkspace.config.ToolRegistryEntry;
import com.scivicslab.aiworkspace.config.ToolRegistryLoader;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Holds what GitHub said the last time it was asked, and asks again on request.
 *
 * <p>A plain object with no knowledge of threads. One actor owns it, so one thread at a time runs
 * these methods and the maps below are ordinary {@link HashMap}s
 * ({@code ActorBasedState_260907_oo01}). This is how k8s-pups's {@code SessionManagerActor} holds
 * its sessions.
 *
 * <p>GitHub is not asked when a screen is drawn. Its REST API allows sixty requests an hour from
 * one address without authentication, and the registry has ten entries, so drawing the dashboard
 * six times would use them all up. It is asked when the {@code Refresh versions} button is
 * pressed, and what it said is kept until the button is pressed again
 * ({@code ToolVersions_260907_oo01}).
 *
 * <p>What is kept lives here and nowhere else. Restarting AI-workspace empties it, and the two
 * lines it feeds are blank until the button is pressed.
 */
public class ToolVersionActor {

    private static final Logger logger = Logger.getLogger(ToolVersionActor.class.getName());

    private final GitHubVersionFetcher fetcher;

    private final Map<String, RemoteVersions> byTool = new HashMap<>();
    private Instant fetchedAt;
    private List<String> failedTools = List.of();

    public ToolVersionActor(GitHubVersionFetcher fetcher) {
        this.fetcher = fetcher;
    }

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

    /** Every tool's versions, keyed by tool name. For the REST response. */
    public Map<String, RemoteVersions> all() {
        return new LinkedHashMap<>(byTool);
    }

    /**
     * Asks GitHub about one tool's repository.
     *
     * <p>One tool at a time, because the screen fills in one tile at a time. Reading all ten in
     * one call took thirty-five seconds during which nothing on the screen moved
     * ({@code ToolVersions_260907_oo01}).
     *
     * @param toolName the tool to read
     * @return its two versions
     * @throws IllegalArgumentException when no registry entry of that name names a repository
     * @throws VersionFetchException when the repository could not be read; the tool keeps what
     *         versions it already had
     */
    public RemoteVersions refreshOne(String toolName) {
        String repository = null;
        for (ToolRegistryEntry entry : ToolRegistryLoader.load()) {
            if (entry.name().equals(toolName)) { repository = entry.githubRepo(); break; }
        }
        if (repository == null || repository.isBlank()) {
            throw new IllegalArgumentException("No GitHub repository configured for " + toolName);
        }

        try {
            RemoteVersions versions = fetcher.fetch(repository);
            byTool.put(toolName, versions);
            fetchedAt = Instant.now();
            forget(toolName);
            return versions;
        } catch (Exception e) {
            remember(toolName);
            logger.warning("Could not read versions for " + toolName
                    + " (" + repository + "): " + e.getMessage());
            throw new VersionFetchException(repository, e);
        }
    }

    /** Adds a tool to the list of those whose repository could not be read. */
    private void remember(String toolName) {
        if (failedTools.contains(toolName)) return;
        List<String> updated = new ArrayList<>(failedTools);
        updated.add(toolName);
        failedTools = List.copyOf(updated);
    }

    /** Removes a tool from that list, now that it has been read. */
    private void forget(String toolName) {
        if (!failedTools.contains(toolName)) return;
        List<String> updated = new ArrayList<>(failedTools);
        updated.remove(toolName);
        failedTools = List.copyOf(updated);
    }
}
