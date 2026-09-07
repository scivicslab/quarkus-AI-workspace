package com.scivicslab.aiworkspace.build;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Bringing an existing build checkout up to date after the remote renamed its default branch.
 *
 * <p>A unit test. It makes a repository in a temporary directory and clones it there; git is a
 * local program and nothing outside the process is reached.
 *
 * <p>The case is the one that happened: {@code scivicslab/quarkus-gpu-broker} was renamed from
 * {@code master} to {@code main} while a checkout from before the rename was still sitting in the
 * build directory, and Build Snapshot answered "git exited with code 128".
 */
@Tag("BranchingBrief_260907_oo01")
class SnapshotBuildUpdateTest {

    @TempDir
    Path tempDir;

    private Path origin;
    private Path checkout;

    /** Runs a command in a directory and returns its exit code, printing nothing. */
    private int run(Path dir, String... command) throws Exception {
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        try (var r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            while (r.readLine() != null) { /* drained so the process is not blocked on a full pipe */ }
        }
        return p.waitFor();
    }

    /** Runs a command that is setup rather than the thing under test, and insists it worked. */
    private void must(Path dir, String... command) throws Exception {
        assertEquals(0, run(dir, command), "setup command failed: " + String.join(" ", command));
    }

    /** The commit the given revision names, or null when it names nothing. */
    private String revision(Path dir, String rev) throws Exception {
        Process p = new ProcessBuilder("git", "rev-parse", rev).directory(dir.toFile()).start();
        String out;
        try (var r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            out = r.readLine();
        }
        return p.waitFor() == 0 ? out : null;
    }

    @BeforeEach
    void makeARepositoryAndACheckoutOfIt() throws Exception {
        origin = tempDir.resolve("origin");
        Files.createDirectories(origin);
        must(origin, "git", "init", "--initial-branch=master", "--quiet");
        must(origin, "git", "config", "user.email", "test@example.invalid");
        must(origin, "git", "config", "user.name", "Test");
        Files.writeString(origin.resolve("README.md"), "first\n");
        must(origin, "git", "add", "README.md");
        must(origin, "git", "commit", "--quiet", "-m", "first");

        checkout = tempDir.resolve("checkout");
        must(tempDir, "git", "clone", "--quiet", origin.toString(), "checkout");
    }

    /** Renames the default branch in the origin and adds a commit to it. */
    private void renameTheDefaultBranchAndCommit() throws Exception {
        must(origin, "git", "branch", "-m", "master", "main");
        Files.writeString(origin.resolve("README.md"), "second\n");
        must(origin, "git", "commit", "--quiet", "-am", "second");
    }

    @Test
    @DisplayName("a checkout from before the rename is brought up to the renamed branch")
    void theCheckoutFollowsTheRename() throws Exception {
        renameTheDefaultBranchAndCommit();

        for (String[] command : SnapshotBuildService.updateCommands()) {
            assertEquals(0, run(checkout, command),
                    "command failed: " + String.join(" ", command));
        }

        assertEquals(revision(origin, "main"), revision(checkout, "HEAD"),
                "the checkout must sit on the commit the renamed default branch points at");
        assertEquals("second\n", Files.readString(checkout.resolve("README.md")));
    }

    @Test
    @DisplayName("resetting to the tracked branch is what failed, and is why origin/HEAD is used")
    void resettingToTheTrackedBranchFailsAfterARename() throws Exception {
        renameTheDefaultBranchAndCommit();
        must(checkout, "git", "fetch", "--all", "--prune");

        assertNotEquals(0, run(checkout, "git", "reset", "--hard", "@{u}"),
                "@{u} names the pruned origin/master and resolves to nothing; this is the 128");
    }

    @Test
    @DisplayName("a checkout with local drift is reset, rename or no rename")
    void localDriftIsDiscarded() throws Exception {
        Files.writeString(checkout.resolve("README.md"), "scribbled over by the last build\n");

        for (String[] command : SnapshotBuildService.updateCommands()) {
            assertEquals(0, run(checkout, command),
                    "command failed: " + String.join(" ", command));
        }

        assertEquals("first\n", Files.readString(checkout.resolve("README.md")));
    }
}
