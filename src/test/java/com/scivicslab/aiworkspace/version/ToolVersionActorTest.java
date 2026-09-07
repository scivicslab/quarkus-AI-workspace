package com.scivicslab.aiworkspace.version;

import com.scivicslab.aiworkspace.config.ToolRegistryEntry;
import com.scivicslab.aiworkspace.config.ToolRegistryLoader;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link ToolVersionActor} does with one tool's answer.
 *
 * <p>A unit test: GitHub is never reached. {@link GitHubVersionFetcher} is subclassed so that the
 * test decides what the answer is, including that there is none.
 *
 * <p>The tool name comes from the registry rather than being written here, so that renaming a tool
 * in {@code ai-workspace-tools.yaml} does not leave this test asking about a name that no longer
 * exists.
 */
@Tag("ToolVersions_260907_oo01")
class ToolVersionActorTest {

    /** A fetcher that answers what the test tells it to, and never opens a connection. */
    private static class ScriptedFetcher extends GitHubVersionFetcher {
        RemoteVersions answer;
        Exception failure;
        String askedAbout;

        @Override
        public RemoteVersions fetch(String repository) throws Exception {
            askedAbout = repository;
            if (failure != null) throw failure;
            return answer;
        }
    }

    private ScriptedFetcher fetcher;
    private ToolVersionActor actor;
    private String toolName;
    private String repository;

    @BeforeEach
    void findAToolWithARepository() {
        fetcher = new ScriptedFetcher();
        actor = new ToolVersionActor(fetcher);
        for (ToolRegistryEntry entry : ToolRegistryLoader.load()) {
            if (entry.githubRepo() != null && !entry.githubRepo().isBlank()) {
                toolName = entry.name();
                repository = entry.githubRepo();
                return;
            }
        }
        throw new IllegalStateException(
                "ai-workspace-tools.yaml names no repository for any tool, so there is nothing to refresh");
    }

    @Test
    void knowsNothingUntilAsked() {
        assertEquals(RemoteVersions.none(), actor.get(toolName));
        assertNull(actor.fetchedAt(), "fetchedAt stays null until a refresh has run");
        assertTrue(actor.failedTools().isEmpty());
    }

    @Test
    void keepsWhatGitHubAnsweredForThatTool() {
        fetcher.answer = new RemoteVersions("4.0.0", "4.1.0-SNAPSHOT");

        RemoteVersions returned = actor.refreshOne(toolName);

        assertEquals(repository, fetcher.askedAbout, "asks about the repository the registry names");
        assertEquals("4.0.0", returned.latestRelease());
        assertEquals("4.1.0-SNAPSHOT", returned.latestSnapshot());
        assertEquals(returned, actor.get(toolName), "and holds it, so the next screen shows it");
        assertNotNull(actor.fetchedAt());
        assertTrue(actor.failedTools().isEmpty());
    }

    @Test
    void asksAboutOneToolOnly() {
        fetcher.answer = new RemoteVersions("4.0.0", "4.1.0-SNAPSHOT");
        actor.refreshOne(toolName);

        assertEquals(1, actor.all().size(),
                "one request reads one repository; the other tiles are asked for separately");
    }

    @Test
    void refusesANameTheRegistryDoesNotKnow() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> actor.refreshOne("no-such-tool"));

        assertTrue(thrown.getMessage().contains("no-such-tool"));
        assertNull(fetcher.askedAbout, "and does not go to GitHub to find that out");
    }

    @Test
    void aToolThatCouldNotBeReadKeepsTheVersionsItHad() {
        fetcher.answer = new RemoteVersions("4.0.0", "4.1.0-SNAPSHOT");
        actor.refreshOne(toolName);

        fetcher.failure = new IOException("connection reset");
        VersionFetchException thrown =
                assertThrows(VersionFetchException.class, () -> actor.refreshOne(toolName));

        assertTrue(thrown.getMessage().contains(repository));
        assertEquals("connection reset", thrown.getCause().getMessage());
        assertEquals("4.0.0", actor.get(toolName).latestRelease(),
                "the failed read must not blank out what was already known");
        assertEquals(java.util.List.of(toolName), actor.failedTools());
    }

    @Test
    void aToolStopsBeingNamedAsFailedOnceItIsRead() {
        fetcher.failure = new IOException("connection reset");
        assertThrows(VersionFetchException.class, () -> actor.refreshOne(toolName));
        assertEquals(java.util.List.of(toolName), actor.failedTools());

        fetcher.failure = null;
        fetcher.answer = new RemoteVersions("4.0.0", "4.1.0-SNAPSHOT");
        actor.refreshOne(toolName);

        assertTrue(actor.failedTools().isEmpty(),
                "the line under the button must not keep naming a tool that has since been read");
    }
}
