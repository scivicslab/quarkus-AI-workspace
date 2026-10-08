package com.scivicslab.aiworkspace.build;

import com.scivicslab.aiworkspace.config.ToolRegistryEntry;
import com.scivicslab.aiworkspace.config.ToolRegistryLoader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which jar Build Snapshot takes out of a repository that builds several.
 *
 * <p>html-saurus used to build one. It now builds one per mode, and the tile here runs the portal:
 * the published site is served by html-saurus-production-server, which is not deployed from this
 * machine. {@code locateUberJar} looks for {@code <base>-<digit>...jar} so that a tool's base name
 * does not also match its plugin jars, and that is exactly what stopped it finding anything —
 * every candidate now has a word after {@code html-saurus-}, not a version. Build Snapshot failed
 * with "No uber-jar matching 'html-saurus-*.jar' found".
 */
class HtmlSaurusArtifactTest {

    @TempDir
    Path repo;

    private ToolRegistryEntry htmlSaurus() {
        return ToolRegistryLoader.load().stream()
                .filter(e -> e.name().equals("html-saurus"))
                .findFirst().orElseThrow(() -> new AssertionError("no html-saurus entry"));
    }

    @Test
    @DisplayName("The entry names the artifact to take, since the link name no longer finds one")
    void theEntryNamesTheArtifact() {
        ToolRegistryEntry e = htmlSaurus();
        assertEquals("html-saurus.jar", e.jarFileName(), "the link in ~/works keeps its name");
        assertNotEquals(e.artifactBase(), "html-saurus",
                "the repository builds no artifact called html-saurus any more, so the entry has "
                        + "to say which of its jars this tile deploys");
        assertEquals("html-saurus-portal-server", e.artifactBase());
    }

    @Test
    @DisplayName("That name finds the portal jar among the four the build leaves behind")
    void itFindsThePortalJarAndNotAnother() throws Exception {
        for (String module : new String[] {"html-saurus-lib", "html-saurus-build-only",
                                           "html-saurus-production-server", "html-saurus-portal-server"}) {
            Path target = repo.resolve(module).resolve("target");
            Files.createDirectories(target);
            Files.write(target.resolve(module + "-2.6.0-SNAPSHOT.jar"), new byte[1024]);
            // The uber-jar sits beside the thin one and is the bigger of the two.
            Files.write(target.resolve(module + "-2.6.0-SNAPSHOT-shaded.jar"), new byte[64 * 1024]);
        }

        Path found = SnapshotBuildService.locateUberJar(repo, htmlSaurus().artifactBase());
        assertEquals("html-saurus-portal-server-2.6.0-SNAPSHOT-shaded.jar",
                found.getFileName().toString(),
                "the portal's uber-jar, not the production server's and not the library's");
    }

    @Test
    @DisplayName("Without the artifact name nothing is found, which is the failure that was reported")
    void theLinkNameAloneFindsNothing() throws Exception {
        Path target = repo.resolve("html-saurus-portal-server").resolve("target");
        Files.createDirectories(target);
        Files.write(target.resolve("html-saurus-portal-server-2.6.0-SNAPSHOT-shaded.jar"), new byte[1024]);

        assertThrows(IllegalStateException.class,
                () -> SnapshotBuildService.locateUberJar(repo, "html-saurus"),
                "what follows html-saurus- is a word, not a version, so the search rejects it");
    }
}
