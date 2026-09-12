package com.scivicslab.aiworkspace.build;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.scivicslab.aiworkspace.config.ToolRegistryEntry;
import com.scivicslab.aiworkspace.config.ToolRegistryLoader;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import com.scivicslab.pojoactor.core.ActorRef;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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

    /**
     * Base URL used to clone repositories. The tools are public GitHub repositories
     * (the "Download Latest" flow already reads them anonymously), so the default
     * is anonymous HTTPS — no SSH key required. Override for GitHub Enterprise or SSH.
     */
    @jakarta.inject.Inject
    com.scivicslab.aiworkspace.actor.AiWorkspaceActorSystem actors;

    @ConfigProperty(name = "ai-workspace.snapshot.git-base-url", defaultValue = "https://github.com")
    String gitBaseUrl;

    /** Maven executable; overridable when mvn is not on the launch PATH. */
    @ConfigProperty(name = "ai-workspace.snapshot.mvn", defaultValue = "mvn")
    String mvnCommand;

    /**
     * Local Maven repository path passed as {@code -Dmaven.repo.local}. Empty = use Maven's default
     * ({@code ~/.m2}). Set this to a path on persistent storage (e.g. an NFS-backed {@code ~/works/.m2})
     * so the dependency cache survives Pod re-creation; otherwise each fresh Pod re-downloads everything.
     */
    @ConfigProperty(name = "ai-workspace.snapshot.maven-repo-local")
    Optional<String> mavenRepoLocal = Optional.empty();

    /** Root directory holding one working checkout per repository. */
    @ConfigProperty(name = "ai-workspace.snapshot.build-dir",
        defaultValue = "${user.home}/.local/share/quarkus-ai-workspace/build")
    String buildDirTemplate;

    /** Directory the built uber-jar is installed into (defaults to the launch directory). */
    @ConfigProperty(name = "ai-workspace.snapshot.works-dir",
        defaultValue = "${user.dir}")
    String worksDirTemplate;

    /**
     * Starts a build for {@code tool} and returns immediately.
     *
     * @param tool         the tool name (for logging/tracking)
     * @param githubRepo   "owner/repo"
     * @param jarFileName  the symlink name in ~/works/ (e.g. "quarkus-chat-ui.jar")
     * @return the created job, already RUNNING on a background thread
     */
    public ActorRef<BuildJobActor> start(String tool, String githubRepo, String jarFileName) {
        return start(tool, githubRepo, jarFileName, List.of());
    }

    /**
     * @param companionJars link names of the jars that accompany the tool's jar, placed in
     *                      ~/works after it ({@code CompanionJars_260912_oo01}); empty for none
     */
    public ActorRef<BuildJobActor> start(String tool, String githubRepo, String jarFileName,
                                         List<String> companionJars) {
        String jobId = UUID.randomUUID().toString();
        ActorRef<BuildJobActor> job = actors.newBuildJob(jobId, tool);
        // The build runs on a virtual thread because waiting for Maven is its whole job. What it
        // learns along the way it tells the actor; it never writes the state itself.
        Thread.ofVirtual().name("snapshot-build-" + tool).start(
            () -> run(job, githubRepo, jarFileName, companionJars == null ? List.of() : companionJars));
        return job;
    }

    /** @return the tracked build, if this portal started it. */
    public Optional<ActorRef<BuildJobActor>> get(String jobId) {
        return actors.buildRegistry().ask(r -> r.get(jobId)).join();
    }

    // ---------------------------------------------------------------
    // Build pipeline (background thread)
    // ---------------------------------------------------------------

    private void run(ActorRef<BuildJobActor> job, String githubRepo, String jarFileName,
                     List<String> companionJars) {
        try {
            buildDependencies(job);
            Path repoDir = cloneOrUpdate(job, githubRepo);
            build(job, repoDir, false, List.of()); // the tool itself is tested (project policy), full build

            if (jarFileName != null && !jarFileName.isBlank()) {
                job.tell(j -> j.step("locating jar"));
                String jarBase = jarFileName.endsWith(".jar")
                    ? jarFileName.substring(0, jarFileName.length() - ".jar".length())
                    : jarFileName;
                Path uberJar = locateUberJar(repoDir, jarBase);
                job.tell(j -> j.append("Found uber-jar: " + uberJar));

                job.tell(j -> j.step("installing to ~/works"));
                Path dest = installToWorks(job, uberJar, jarFileName);
                // The jars that accompany the tool's jar — its plugins — go the same way, each
                // found by its own base name and linked under its own name.
                for (String companion : companionJars) {
                    String base = companion.endsWith(".jar")
                        ? companion.substring(0, companion.length() - ".jar".length())
                        : companion;
                    Path companionJar = locateUberJar(repoDir, base);
                    job.tell(j -> j.append("Found companion jar: " + companionJar));
                    installToWorks(job, companionJar, companion);
                }
                job.tell(j -> j.resultFile(dest.getFileName().toString()));
                job.tell(j -> j.append("SUCCESS: installed " + dest));
                logger.info("Snapshot build succeeded for " + job.ask(j -> j.tool()).join() + " → " + dest);
            } else {
                // Library build: artifacts installed to ~/.m2 by mvn install; nothing to deploy.
                job.tell(j -> j.append("Library build complete: artifacts installed to ~/.m2"));
                logger.info("Snapshot library build succeeded for " + job.ask(j -> j.tool()).join());
            }

            job.tell(j -> j.step("done"));
            job.tell(j -> j.succeeded());
        } catch (Exception e) {
            job.tell(j -> j.failed(e.getMessage()));
            
            job.tell(j -> j.append("ERROR: " + e.getMessage()));
            logger.warning("Snapshot build failed for " + job.ask(j -> j.tool()).join() + ": " + e.getMessage());
        }
    }

    /**
     * Builds this tool's declared dependency libraries — transitively and in dependency order —
     * installing each to {@code ~/.m2} before the tool itself is built. This is what makes Build
     * Snapshot one-touch: an internal {@code -SNAPSHOT} dependency that is not published to Maven
     * Central is produced from source here instead of failing the tool's own dependency resolution.
     */
    private void buildDependencies(ActorRef<BuildJobActor> job) throws Exception {
        List<ToolRegistryEntry> ordered = dependencyBuildOrder(job.ask(j -> j.tool()).join());
        if (ordered.isEmpty()) return;
        job.tell(j -> j.append("Dependencies to build first (in order): "
            + ordered.stream().map(ToolRegistryEntry::name).toList()));
        for (ToolRegistryEntry dep : ordered) {
            if (dep.githubRepo() == null || dep.githubRepo().isBlank()) {
                job.tell(j -> j.append("── Skipping dependency " + dep.name() + ": no github repo configured"));
                continue;
            }
            job.tell(j -> j.step("dependency: " + dep.name()));
            job.tell(j -> j.append("────── Building dependency: " + dep.name() + " (" + dep.githubRepo() + ")"));
            Path depDir = cloneOrUpdate(job, dep.githubRepo());
            build(job, depDir, true, dep.modules()); // library: mvn install to ~/.m2 (tests skipped, only needed modules)
        }
        String toolName = job.ask(j -> j.tool()).join();
        job.tell(j -> j.append("────── Dependencies ready; building " + toolName));
    }

    /**
     * Returns the transitive dependency closure of {@code toolName} in build order (each dependency
     * before the entry that needs it), excluding the tool itself. Entries appear once; dependency
     * cycles are broken; names not present in the registry are skipped with a warning.
     */
    private List<ToolRegistryEntry> dependencyBuildOrder(String toolName) {
        Map<String, ToolRegistryEntry> byName = new LinkedHashMap<>();
        for (ToolRegistryEntry e : ToolRegistryLoader.load()) {
            byName.put(e.name(), e);
        }
        List<ToolRegistryEntry> ordered = new ArrayList<>();
        visitDependencies(toolName, byName, new HashSet<>(), new HashSet<>(), ordered, true);
        return ordered;
    }

    private void visitDependencies(String name, Map<String, ToolRegistryEntry> byName,
            Set<String> done, Set<String> onPath, List<ToolRegistryEntry> ordered, boolean isRoot) {
        if (done.contains(name)) return;
        if (!onPath.add(name)) {
            logger.warning("Dependency cycle at '" + name + "'; breaking the cycle");
            return;
        }
        ToolRegistryEntry e = byName.get(name);
        if (e == null) {
            if (!isRoot) logger.warning("Unknown dependency '" + name + "' — skipping");
        } else {
            for (String dep : e.dependsOn()) {
                visitDependencies(dep, byName, done, onPath, ordered, false);
            }
            if (!isRoot) ordered.add(e); // post-order: dependencies precede dependents; exclude the root
        }
        onPath.remove(name);
        done.add(name);
    }

    /**
     * The commands that bring an existing checkout up to the remote's default branch, discarding
     * whatever the last build left behind.
     *
     * <p>The reset names {@code origin/HEAD}, not {@code @{u}}. Those are different things: one is
     * the branch the remote says is its default, the other is whatever branch this checkout
     * happens to track. They differ the moment the default branch is renamed — the tracked branch
     * is pruned away, {@code @{u}} resolves to nothing, and git exits 128. That is what happened
     * when {@code scivicslab/quarkus-gpu-broker} went from {@code master} to {@code main}.
     *
     * <p>{@code set-head --auto} asks the remote which branch is the default and rewrites
     * {@code origin/HEAD} to match, so a rename since the last build is picked up here rather than
     * needing the checkout to be thrown away.
     *
     * @return the commands, to be run in the checkout in order
     */
    static List<String[]> updateCommands() {
        return List.of(
            new String[]{"git", "fetch", "--all", "--prune"},
            new String[]{"git", "remote", "set-head", "origin", "--auto"},
            new String[]{"git", "reset", "--hard", "origin/HEAD"});
    }

    private Path cloneOrUpdate(ActorRef<BuildJobActor> job, String githubRepo) throws Exception {
        Path buildRoot = Path.of(expand(buildDirTemplate));
        Files.createDirectories(buildRoot);
        String leaf = githubRepo.contains("/")
            ? githubRepo.substring(githubRepo.indexOf('/') + 1)
            : githubRepo;
        Path repoDir = buildRoot.resolve(leaf);

        if (Files.isDirectory(repoDir.resolve(".git"))) {
            job.tell(j -> j.step("git pull"));
            job.tell(j -> j.append("Updating existing checkout: " + repoDir));
            for (String[] command : updateCommands()) {
                exec(job, repoDir, command);
            }
        } else {
            job.tell(j -> j.step("git clone"));
            String base = gitBaseUrl.endsWith("/")
                ? gitBaseUrl.substring(0, gitBaseUrl.length() - 1)
                : gitBaseUrl;
            String url = base + "/" + githubRepo + ".git";
            job.tell(j -> j.append("Cloning " + url + " → " + repoDir));
            exec(job, buildRoot, "git", "clone", url, leaf);
        }
        return repoDir;
    }

    private void build(ActorRef<BuildJobActor> job, Path repoDir, boolean skipTests, List<String> modules) throws Exception {
        job.tell(j -> j.step("clean target"));
        // `mvn clean` is unreliable in these projects; remove target dirs directly. The whole
        // subtree, not the directory entry: deleteIfExists on a non-empty directory fails, so
        // deleting only the entries named "target" left every old jar in place. locateUberJar
        // picks the largest candidate, and a stale jar that grew past the fresh one was then
        // installed as the build's result — a SUCCESS whose artifact was three days old.
        try (Stream<Path> tree = Files.walk(repoDir)) {
            List<Path> targets = tree
                .filter(p -> p.getFileName().toString().equals("target") && Files.isDirectory(p))
                .toList();
            for (Path target : targets) {
                try (Stream<Path> contents = Files.walk(target)) {
                    contents.sorted(Comparator.reverseOrder())
                            .forEach(SnapshotBuildService::deleteQuietly);
                }
            }
        }
        job.tell(j -> j.step("mvn install"));
        // Optionally pin the local repo onto persistent storage so the dependency cache survives Pod
        // re-creation (e.g. an NFS-backed ~/works/.m2). Empty -> Maven's default ~/.m2.
        // -Dgpg.skip=true: several tools sign artifacts with maven-gpg-plugin on `install` for
        // Maven Central publishing. A build pod has no signing key, so signing fails (exit 2) even
        // though compile/package succeed. Build Snapshot only needs the jar installed locally, not
        // signed, so skip signing.
        java.util.List<String> cmd = new java.util.ArrayList<>(
                java.util.List.of(mvnCommand, "install", "-DskipITs", "-B", "-Dgpg.skip=true"));
        // Build only the requested modules (plus the reactor deps they need, via -am) when the
        // library declares them. A multi-module library may contain sibling modules a consumer does
        // not need and that may not build on their own, so building the whole reactor would fail.
        if (modules != null && !modules.isEmpty()) {
            cmd.add("-pl");
            cmd.add(String.join(",", modules));
            cmd.add("-am");
        }
        // Dependency libraries are built only to install their artifacts to ~/.m2; running their
        // full unit-test suites (some of which are environment-dependent, e.g. plugin-ssh) is not
        // the consumer's job and would let an unrelated dependency's test failure block the tool.
        // The tool itself is still tested (per project policy).
        if (skipTests) cmd.add("-DskipTests");
        if (mavenRepoLocal.isPresent() && !mavenRepoLocal.get().isBlank()) {
            cmd.add("-Dmaven.repo.local=" + mavenRepoLocal.get().trim());
        }
        job.tell(j -> j.append("Running " + String.join(" ", cmd)
                + (skipTests ? " (tests skipped: dependency build)" : " (unit tests run; integration tests skipped)")));
        exec(job, repoDir, cmd.toArray(new String[0]));
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
                    // "<base>-<version>.jar": what follows the base is a version, so a base that is
                    // itself the prefix of a longer artifact name (a body and its plugin jars) does
                    // not match that artifact.
                    return n.startsWith(jarBase + "-")
                        && n.length() > jarBase.length() + 1
                        && Character.isDigit(n.charAt(jarBase.length() + 1))
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

    private Path installToWorks(ActorRef<BuildJobActor> job, Path uberJar, String jarFileName) throws Exception {
        Path worksDir = Path.of(expand(worksDirTemplate));
        Files.createDirectories(worksDir);
        String versioned = uberJar.getFileName().toString();
        Path dest = worksDir.resolve(versioned);
        Files.copy(uberJar, dest, StandardCopyOption.REPLACE_EXISTING);
        job.tell(j -> j.append("Copied " + versioned + " → " + dest));

        Path symlink = worksDir.resolve(jarFileName);
        Files.deleteIfExists(symlink);
        Files.createSymbolicLink(symlink, Path.of(versioned));
        job.tell(j -> j.append("Symlink " + jarFileName + " → " + versioned));
        return dest;
    }

    // ---------------------------------------------------------------
    // Process execution
    // ---------------------------------------------------------------

    private void exec(ActorRef<BuildJobActor> job, Path workingDir, String... command) throws Exception {
        job.tell(j -> j.append("$ " + String.join(" ", command)));
        ProcessBuilder pb = new ProcessBuilder(command)
            .directory(workingDir.toFile())
            .redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                // Fixed per iteration: the loop variable itself changes, and the actor reads it
                // after this thread has moved on.
                String printed = line;
                job.tell(j -> j.append(printed));
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
        return com.scivicslab.aiworkspace.config.PathTemplate.expand(template);
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
