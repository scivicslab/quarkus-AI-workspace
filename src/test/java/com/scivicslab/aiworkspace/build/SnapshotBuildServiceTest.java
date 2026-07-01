package com.scivicslab.aiworkspace.build;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SnapshotBuildService#locateUberJar}, the logic that picks
 * the runnable uber-jar out of a freshly built multi-module tree.
 */
class SnapshotBuildServiceTest {

    /** Writes a file of the given byte length so size-based selection is exercised. */
    private static Path jar(Path dir, String name, int bytes) throws Exception {
        Files.createDirectories(dir);
        Path p = dir.resolve(name);
        Files.write(p, new byte[bytes]);
        return p;
    }

    @Test
    @DisplayName("picks the app module uber-jar, ignoring sibling module and classifier jars")
    void picksUberJar(@TempDir Path repo) throws Exception {
        // A multi-module layout like quarkus-chat-ui: the big uber-jar lives in app/target.
        Path appTarget = repo.resolve("app/target");
        Path coreTarget = repo.resolve("core/target");
        Path uber = jar(appTarget, "quarkus-chat-ui-2.5.0-SNAPSHOT.jar", 5_000_000);
        jar(appTarget, "quarkus-chat-ui-2.5.0-SNAPSHOT-sources.jar", 10_000);
        jar(appTarget, "quarkus-chat-ui-2.5.0-SNAPSHOT-javadoc.jar", 10_000);
        jar(coreTarget, "chat-ui-core-2.5.0-SNAPSHOT.jar", 200_000);

        Path found = SnapshotBuildService.locateUberJar(repo, "quarkus-chat-ui");
        assertThat(found).isEqualTo(uber);
    }

    @Test
    @DisplayName("single-module layout: picks the jar directly under target")
    void singleModule(@TempDir Path repo) throws Exception {
        Path uber = jar(repo.resolve("target"), "quarkus-chat-ui3-1.0.0-SNAPSHOT.jar", 4_000_000);
        // Quarkus leaves the thin jar as .jar.original — must not be selected.
        Files.write(repo.resolve("target/quarkus-chat-ui3-1.0.0-SNAPSHOT.jar.original"), new byte[500]);

        Path found = SnapshotBuildService.locateUberJar(repo, "quarkus-chat-ui3");
        assertThat(found).isEqualTo(uber);
    }

    @Test
    @DisplayName("does not confuse a longer-named sibling (chat-ui vs chat-ui3)")
    void prefixIsNotConfused(@TempDir Path repo) throws Exception {
        jar(repo.resolve("target"), "quarkus-chat-ui3-1.0.0-SNAPSHOT.jar", 4_000_000);
        assertThatThrownBy(() -> SnapshotBuildService.locateUberJar(repo, "quarkus-chat-ui"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("ignores matching jars that are not under a target directory")
    void ignoresNonTarget(@TempDir Path repo) throws Exception {
        jar(repo.resolve("somedir"), "quarkus-chat-ui-2.5.0-SNAPSHOT.jar", 5_000_000);
        assertThatThrownBy(() -> SnapshotBuildService.locateUberJar(repo, "quarkus-chat-ui"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("throws when no matching uber-jar exists")
    void noMatch(@TempDir Path repo) throws Exception {
        jar(repo.resolve("target"), "something-else-1.0.jar", 1000);
        assertThatThrownBy(() -> SnapshotBuildService.locateUberJar(repo, "quarkus-chat-ui"))
            .isInstanceOf(IllegalStateException.class);
    }
}
