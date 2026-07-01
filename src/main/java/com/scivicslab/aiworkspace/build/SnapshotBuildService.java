package com.scivicslab.aiworkspace.build;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Builds a tool from its GitHub source and installs the resulting uber-jar into
 * {@code ~/works/}. This is the "development snapshot" counterpart to the
 * "Download Latest" flow: instead of fetching a published release asset, it
 * clones (or updates) the repository, runs a Maven build, and copies the freshly
 * built uber-jar over — useful when the tool is only available as an unreleased
 * {@code -SNAPSHOT}.
 *
 * <p>Builds take minutes, so they run on a background virtual thread and are
 * tracked as {@link BuildJob}s that the REST layer polls. Nothing here blocks
 * the HTTP request thread.
 */
@ApplicationScoped
public class SnapshotBuildService {

    private static final Logger logger = Logger.getLogger(SnapshotBuildService.class.getName());

    /** Maximum number of output lines retained per job (rolling tail). */
    private static final int MAX_LOG_LINES = 800;

    /** SSH host alias used to clone repositories (matches the developer's ~/.ssh/config). */
    @ConfigProperty(name = "ai-workspace.snapshot.git-host", defaultValue = "github-scivicslab")
    String gitHost;

    /** Maven executable; overridable when mvn is not on the launch PATH. */
    @ConfigProperty(name = "ai-workspace.snapshot.mvn", defaultValue = "mvn")
    String mvnCommand;

    /** Root directory holding one working checkout per repository. */
    @ConfigProperty(name = "ai-workspace.snapshot.build-dir",
        defaultValue = "${user.home}/.local/share/quarkus-ai-workspace/build")
    String buildDirTemplate;

    private final ConcurrentHashMap<String, BuildJob> jobs = new ConcurrentHashMap<>();

    /** State of a snapshot build. */
    public enum State { RUNNING, SUCCESS, FAILED }

    /** A single snapshot-build job, updated in place as the build progresses. */
    public static final class BuildJob {
        final String id;
        final String tool;
        volatile State state = State.RUNNING;
        volatile String step = "queued";
        volatile String resultFile;   // versioned jar name once built
        volatile String error;
        final Deque<String> log = new ArrayDeque<>();

        BuildJob(String id, String tool) {
            this.id = id;
            this.tool = tool;
        }

        public String id()          { return id; }
        public String tool()        { return tool; }
        public State state()        { return state; }
        public String step()        { return step; }
        public String resultFile()  { return resultFile; }
        public String error()       { return error; }

        /** @return the last {@code n} log lines, oldest first. */
        public synchronized List<String> tail(int n) {
            int size = log.size();
            int skip = Math.max(0, size - n);
            return log.stream().skip(skip).toList();
        }

        synchronized void append(String line) {
            log.addLast(line);
            while (log.size() > MAX_LOG_LINES) log.removeFirst();
        }
    }

    /**
     * Starts a build for {@code tool} and returns immediately.
     *
     * @param tool         the tool name (for logging/tracking)
     * @param githubRepo   "owner/repo"
     * @param jarFileName  the symlink name in ~/works/ (e.g. "quarkus-chat-ui.jar")
     * @return the created job, already RUNNING on a background thread
     */
    public BuildJob start(String tool, String githubRepo, String jarFileName) {
        BuildJob job = new BuildJob(UUID.randomUUID().toString(), tool);
        jobs.put(job.id, job);
        Thread.ofVirtual().name("snapshot-build-" + tool).start(
            () -> run(job, githubRepo, jarFileName));
        return job;
    }

    /** @return the tracked job, if any. */
    public Optional<BuildJob> get(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    // ---------------------------------------------------------------
    // Build pipeline (background thread)
    // ---------------------------------------------------------------

    private void run(BuildJob job, String githubRepo, String jarFileName) {
        try {
            Path repoDir = cloneOrUpdate(job, githubRepo);
            build(job, repoDir);

            job.step = "locating jar";
            String jarBase = jarFileName.endsWith(".jar")
                ? jarFileName.substring(0, jarFileName.length() - ".jar".length())
                : jarFileName;
            Path uberJar = locateUberJar(repoDir, jarBase);
            job.append("Found uber-jar: " + uberJar);

            job.step = "installing to ~/works";
            Path dest = installToWorks(job, uberJar, jarFileName);
            job.resultFile = dest.getFileName().toString();

            job.step = "done";
            job.state = State.SUCCESS;
            job.append("SUCCESS: installed " + dest);
            logger.info("Snapshot build succeeded for " + job.tool + " → " + dest);
        } catch (Exception e) {
            job.error = e.getMessage();
            job.state = State.FAILED;
            job.append("ERROR: " + e.getMessage());
            logger.warning("Snapshot build failed for " + job.tool + ": " + e.getMessage());
        }
    }

    private Path cloneOrUpdate(BuildJob job, String githubRepo) throws Exception {
        Path buildRoot = Path.of(expand(buildDirTemplate));
        Files.createDirectories(buildRoot);
        String leaf = githubRepo.contains("/")
            ? githubRepo.substring(githubRepo.indexOf('/') + 1)
            : githubRepo;
        Path repoDir = buildRoot.resolve(leaf);

        if (Files.isDirectory(repoDir.resolve(".git"))) {
            job.step = "git pull";
            job.append("Updating existing checkout: " + repoDir);
            // Discard local drift, then fast-forward to the remote's default branch.
            exec(job, repoDir, "git", "fetch", "--all", "--prune");
            exec(job, repoDir, "git", "reset", "--hard", "@{u}");
        } else {
            job.step = "git clone";
            String url = "git@" + gitHost + ":" + githubRepo + ".git";
            job.append("Cloning " + url + " → " + repoDir);
            exec(job, buildRoot, "git", "clone", url, leaf);
        }
        return repoDir;
    }

    private void build(BuildJob job, Path repoDir) throws Exception {
        job.step = "clean target";
        // `mvn clean` is unreliable in these projects; remove target dirs directly.
        try (Stream<Path> tree = Files.walk(repoDir)) {
            tree.filter(p -> p.getFileName().toString().equals("target") && Files.isDirectory(p))
                .sorted(Comparator.reverseOrder())
                .forEach(SnapshotBuildService::deleteQuietly);
        }
        job.step = "mvn install";
        job.append("Running " + mvnCommand + " install -DskipITs (unit tests run; integration tests skipped)");
        // Unit tests run (no -DskipTests, per project policy); integration tests
        // need a k8s cluster and are skipped here.
        exec(job, repoDir, mvnCommand, "install", "-DskipITs", "-B");
    }

    /**
     * Finds the runnable uber-jar produced by the build. Quarkus names it
     * {@code <artifactId>-<version>.jar} under a module's {@code target/}; we match
     * on the tool's jar base name and pick the largest candidate (the uber-jar is
     * far bigger than any thin/test jar).
     */
    static Path locateUberJar(Path repoDir, String jarBase) throws Exception {
        try (Stream<Path> tree = Files.walk(repoDir)) {
            return tree
                .filter(Files::isRegularFile)
                .filter(p -> p.getParent() != null
                    && p.getParent().getFileName().toString().equals("target"))
                .filter(p -> {
                    String n = p.getFileName().toString();
                    return n.startsWith(jarBase + "-")
                        && n.endsWith(".jar")
                        && !n.endsWith("-sources.jar")
                        && !n.endsWith("-javadoc.jar")
                        && !n.endsWith("-tests.jar");
                })
                .max(Comparator.comparingLong(SnapshotBuildService::sizeQuietly))
                .orElseThrow(() -> new IllegalStateException(
                    "No uber-jar matching '" + jarBase + "-*.jar' found under " + repoDir));
        }
    }

    private Path installToWorks(BuildJob job, Path uberJar, String jarFileName) throws Exception {
        Path worksDir = Path.of(System.getProperty("user.dir"));
        String versioned = uberJar.getFileName().toString();
        Path dest = worksDir.resolve(versioned);
        Files.copy(uberJar, dest, StandardCopyOption.REPLACE_EXISTING);
        job.append("Copied " + versioned + " → " + dest);

        Path symlink = worksDir.resolve(jarFileName);
        Files.deleteIfExists(symlink);
        Files.createSymbolicLink(symlink, Path.of(versioned));
        job.append("Symlink " + jarFileName + " → " + versioned);
        return dest;
    }

    // ---------------------------------------------------------------
    // Process execution
    // ---------------------------------------------------------------

    private void exec(BuildJob job, Path workingDir, String... command) throws Exception {
        job.append("$ " + String.join(" ", command));
        ProcessBuilder pb = new ProcessBuilder(command)
            .directory(workingDir.toFile())
            .redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                job.append(line);
            }
        }
        int code = p.waitFor();
        if (code != 0) {
            throw new IllegalStateException(
                command[0] + " exited with code " + code + " (see build log)");
        }
    }

    // ---------------------------------------------------------------
    // Small helpers
    // ---------------------------------------------------------------

    /** Expands {@code ${user.home}} and {@code ${user.dir}} in a path template. */
    private static String expand(String template) {
        return template
            .replace("${user.home}", System.getProperty("user.home"))
            .replace("${user.dir}", System.getProperty("user.dir"));
    }

    private static long sizeQuietly(Path p) {
        try {
            return Files.size(p);
        } catch (Exception e) {
            return -1;
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (Exception e) {
            // Best-effort cleanup; a leftover file will not break the build.
        }
    }
}
