package com.scivicslab.aiworkspace.build;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test for {@link SnapshotBuildService}.
 *
 * <p>Touches external services (GitHub over SSH and a real Maven build), so it is
 * an {@code *IT} rather than a unit test. It drives the full pipeline against a
 * real, small target — {@code quarkus-chat-ui3} (single module, fast build) —
 * cloning into a temp directory and installing into a temp "works" directory so
 * the developer's real {@code ~/works} is untouched.
 *
 * <p>The tool is a public GitHub repository, so no credentials are needed — only
 * network reachability to github.com plus {@code git} and {@code mvn} on PATH and
 * a populated {@code ~/.m2}. When those are unavailable the test is skipped (not
 * failed) so a plain {@code mvn verify} on a fresh box does not break.
 */
class SnapshotBuildIT {

    private static final String TOOL = "quarkus-chat-ui3";
    private static final String REPO = "scivicslab/quarkus-chat-ui3";
    private static final String JAR  = "quarkus-chat-ui3.jar";
    private static final String CLONE_URL = "https://github.com/" + REPO + ".git";

    @Test
    @DisplayName("clones, builds and installs the uber-jar into the works directory")
    void start_forSnapshotRepo_clonesBuildsAndInstallsUberJar(
            @TempDir Path buildRoot, @TempDir Path worksRoot) throws Exception {
        assumeToolchainAndNetwork();

        SnapshotBuildService svc = new SnapshotBuildService();
        svc.gitBaseUrl = "https://github.com";
        svc.mvnCommand = "mvn";
        svc.buildDirTemplate = buildRoot.toString();
        svc.worksDirTemplate = worksRoot.toString();

        SnapshotBuildService.BuildJob job = svc.start(TOOL, REPO, JAR);

        // Poll until terminal. A clean chat-ui3 build is seconds; allow generous slack
        // for the git clone and dependency resolution.
        SnapshotBuildService.State state = awaitTerminal(svc, job.id(), Duration.ofMinutes(8));

        String log = String.join("\n", job.tail(800));
        assertThat(state)
            .as("build state (log tail:\n%s\n)", log)
            .isEqualTo(SnapshotBuildService.State.SUCCESS);

        // The versioned uber-jar is installed into the works directory.
        assertThat(job.resultFile()).isNotNull();
        Path installed = worksRoot.resolve(job.resultFile());
        assertThat(installed).exists().isRegularFile();
        assertThat(job.resultFile()).startsWith("quarkus-chat-ui3-").endsWith(".jar");

        // The installed jar is a valid, non-empty archive (not a truncated/corrupt copy).
        try (ZipFile zf = new ZipFile(installed.toFile())) {
            assertThat(zf.size()).isGreaterThan(0);
        }

        // The symlink ~/works/<jar> points at the versioned file.
        Path symlink = worksRoot.resolve(JAR);
        assertThat(Files.isSymbolicLink(symlink)).isTrue();
        assertThat(Files.readSymbolicLink(symlink).toString()).isEqualTo(job.resultFile());
    }

    /**
     * Skips the test (rather than failing) unless git, mvn, and anonymous access to
     * the public repo over HTTPS are all available. This keeps {@code mvn verify}
     * green on a machine without a network or build toolchain.
     */
    private void assumeToolchainAndNetwork() {
        Assumptions.assumeTrue(runsOk("git", "--version"), "git not available on PATH");
        Assumptions.assumeTrue(runsOk("mvn", "-v"), "mvn not available on PATH");
        // GIT_TERMINAL_PROMPT=0 makes a private/unreachable repo fail fast instead of
        // blocking on a credential prompt.
        Assumptions.assumeTrue(runsOk(java.util.Map.of("GIT_TERMINAL_PROMPT", "0"),
                "git", "ls-remote", CLONE_URL, "HEAD"),
            "public repo not reachable anonymously over HTTPS: " + CLONE_URL);
    }

    private static boolean runsOk(String... command) {
        return runsOk(java.util.Map.of(), command);
    }

    private static boolean runsOk(java.util.Map<String, String> env, String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
            pb.environment().putAll(env);
            Process p = pb.start();
            p.getInputStream().readAllBytes(); // drain so the process can exit
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private SnapshotBuildService.State awaitTerminal(
            SnapshotBuildService svc, String jobId, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            SnapshotBuildService.BuildJob j = svc.get(jobId).orElseThrow();
            if (j.state() != SnapshotBuildService.State.RUNNING) {
                return j.state();
            }
            Thread.sleep(2000);
        }
        return SnapshotBuildService.State.RUNNING; // timed out
    }
}
