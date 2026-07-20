package com.scivicslab.aiworkspace.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * Manual end-to-end driver for {@link SnapshotBuildService}.
 *
 * <p>This is intentionally a plain {@code main()} program and <strong>not</strong> a JUnit
 * {@code *IT}/{@code *Test}: it touches external services (GitHub over HTTPS and a real Maven
 * build of an internal SNAPSHOT project whose dependencies must already be present in
 * {@code ~/.m2}). It must therefore never run as part of {@code mvn install} — the class name
 * matches neither the surefire ({@code *Test}) nor the failsafe ({@code *IT}) inclusion
 * patterns, so the build lifecycle ignores it entirely.
 *
 * <p>Run it explicitly only when you want to exercise the full clone → build → install pipeline:
 *
 * <pre>{@code
 *   # from the command line (opt-in profile, test classpath):
 *   mvn -Pe2e-snapshot-build test-compile exec:java
 *
 *   # or from an IDE: run SnapshotBuildE2E.main directly.
 * }</pre>
 *
 * <p>Exit codes: {@code 0} = pass, {@code 1} = failure (a log tail / stack trace is printed),
 * {@code 2} = skipped because the toolchain or network was unavailable.
 */
public final class SnapshotBuildE2E {

    private static final String TOOL = "quarkus-chat-ui3";
    private static final String REPO = "scivicslab/quarkus-chat-ui3";
    private static final String JAR  = "quarkus-chat-ui3.jar";
    private static final String CLONE_URL = "https://github.com/" + REPO + ".git";

    private SnapshotBuildE2E() {
    }

    public static void main(String[] args) throws Exception {
        if (!toolchainAndNetworkAvailable()) {
            System.err.println("SKIPPED: git, mvn, and anonymous access to " + CLONE_URL
                    + " are not all available.");
            System.exit(2);
            return;
        }

        Path buildRoot = Files.createTempDirectory("snapshot-build-e2e-build");
        Path worksRoot = Files.createTempDirectory("snapshot-build-e2e-works");
        int exitCode;
        try {
            runScenario(buildRoot, worksRoot);
            System.out.println("PASS: SnapshotBuildService cloned, built and installed the uber-jar.");
            exitCode = 0;
        } catch (AssertionError failure) {
            System.err.println("FAIL: " + failure.getMessage());
            exitCode = 1;
        } catch (Exception error) {
            System.err.println("ERROR: unexpected failure while driving the pipeline.");
            error.printStackTrace();
            exitCode = 1;
        } finally {
            deleteRecursively(buildRoot);
            deleteRecursively(worksRoot);
        }
        System.exit(exitCode);
    }

    /**
     * Drives the full pipeline against the real {@code quarkus-chat-ui3} repository — a small,
     * single-module target — cloning into {@code buildRoot} and installing into {@code worksRoot}
     * so the developer's real {@code ~/works} is untouched.
     */
    private static void runScenario(Path buildRoot, Path worksRoot) throws Exception {
        SnapshotBuildService svc = new SnapshotBuildService();
        svc.gitBaseUrl = "https://github.com";
        svc.mvnCommand = "mvn";
        svc.buildDirTemplate = buildRoot.toString();
        svc.worksDirTemplate = worksRoot.toString();

        SnapshotBuildService.BuildJob job = svc.start(TOOL, REPO, JAR);

        // Poll until terminal. A clean chat-ui3 build is seconds; allow generous slack for the
        // git clone and dependency resolution.
        SnapshotBuildService.State state = awaitTerminal(svc, job.id(), Duration.ofMinutes(8));

        String log = String.join("\n", job.tail(800));
        check(state == SnapshotBuildService.State.SUCCESS,
                "expected build state SUCCESS but was " + state + "\nlog tail:\n" + log);

        // The versioned uber-jar is installed into the works directory.
        check(job.resultFile() != null, "resultFile() was null");
        Path installed = worksRoot.resolve(job.resultFile());
        check(Files.isRegularFile(installed), "installed uber-jar is missing: " + installed);
        check(job.resultFile().startsWith("quarkus-chat-ui3-") && job.resultFile().endsWith(".jar"),
                "unexpected resultFile name: " + job.resultFile());

        // The installed jar is a valid, non-empty archive (not a truncated/corrupt copy).
        try (ZipFile zf = new ZipFile(installed.toFile())) {
            check(zf.size() > 0, "installed jar is empty or corrupt: " + installed);
        }

        // The symlink <worksRoot>/<jar> points at the versioned file.
        Path symlink = worksRoot.resolve(JAR);
        check(Files.isSymbolicLink(symlink), "expected a symlink at " + symlink);
        check(Files.readSymbolicLink(symlink).toString().equals(job.resultFile()),
                "symlink target mismatch: " + Files.readSymbolicLink(symlink) + " != " + job.resultFile());
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /**
     * True only when git, mvn, and anonymous access to the public repo over HTTPS are all
     * available, mirroring the preconditions the pipeline needs.
     */
    private static boolean toolchainAndNetworkAvailable() {
        // GIT_TERMINAL_PROMPT=0 makes a private/unreachable repo fail fast instead of blocking
        // on a credential prompt.
        return runsOk("git", "--version")
                && runsOk("mvn", "-v")
                && runsOk(Map.of("GIT_TERMINAL_PROMPT", "0"), "git", "ls-remote", CLONE_URL, "HEAD");
    }

    private static boolean runsOk(String... command) {
        return runsOk(Map.of(), command);
    }

    private static boolean runsOk(Map<String, String> env, String... command) {
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

    private static SnapshotBuildService.State awaitTerminal(
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

    private static void deleteRecursively(Path root) {
        if (root == null) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignore) {
                    // best-effort cleanup of a temp directory
                }
            });
        } catch (Exception ignore) {
            // best-effort cleanup of a temp directory
        }
    }
}
