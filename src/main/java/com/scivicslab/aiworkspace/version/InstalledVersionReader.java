package com.scivicslab.aiworkspace.version;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads the version of the jar file a tool's symbolic link currently points at.
 *
 * <p>{@code ~/works} holds one symbolic link per tool, named as the registry's {@code jar} value
 * says, pointing at a jar file whose name carries the version:
 *
 * <pre>
 * html-saurus.jar -&gt; html-saurus-2.3.0-SNAPSHOT.jar
 * </pre>
 *
 * <p>Both buttons that change what is installed leave the directory in this shape:
 * {@code Download Latest Release} and {@code Build Snapshot} each copy a versioned jar file in and
 * repoint the link at it (see {@code ToolVersions_260907_oo01}).
 */
@ApplicationScoped
public class InstalledVersionReader {

    /** The directory the jar files are installed into. Same setting the snapshot build uses. */
    @ConfigProperty(name = "ai-workspace.snapshot.works-dir", defaultValue = "${user.dir}")
    String worksDirTemplate;

    /**
     * Returns the version the tool's symbolic link points at.
     *
     * @param jarFileName the link's name, for example {@code html-saurus.jar}
     * @return the version, or an empty string when there is no link, when what is there is a
     *         regular file rather than a link, or when the name it points at carries no version
     */
    public String read(String jarFileName) {
        if (jarFileName == null || jarFileName.isBlank()) return "";

        Path link = Path.of(expand(worksDirTemplate)).resolve(jarFileName);
        if (!Files.isSymbolicLink(link)) return "";

        String target;
        try {
            target = Files.readSymbolicLink(link).getFileName().toString();
        } catch (Exception e) {
            return "";
        }
        return versionOf(target, jarFileName);
    }

    /** Quarkus writes its runnable jar as {@code <artifact>-<version>-runner.jar}. */
    private static final String RUNNER_SUFFIX = "-runner";

    /**
     * Takes the version out of a versioned jar file's name.
     *
     * <p>{@code html-saurus-2.3.0-SNAPSHOT.jar} with a link named {@code html-saurus.jar} leaves
     * {@code 2.3.0-SNAPSHOT}. A name that does not have the link's own name as its prefix carries
     * no version this can read.
     *
     * <p>{@code code-raptor-1.1.0-SNAPSHOT-runner.jar} leaves {@code 1.1.0-SNAPSHOT}: the
     * {@code -runner} Quarkus appends to a runnable jar is not part of the version, and its pom
     * declares {@code 1.1.0-SNAPSHOT}. Leaving it on would show the same version twice in two
     * different spellings, on two lines meant to be read against each other.
     */
    static String versionOf(String targetFileName, String jarFileName) {
        if (!targetFileName.endsWith(".jar")) return "";
        String base = jarFileName.endsWith(".jar")
                ? jarFileName.substring(0, jarFileName.length() - ".jar".length())
                : jarFileName;
        String prefix = base + "-";
        if (!targetFileName.startsWith(prefix)) return "";
        String version = targetFileName.substring(prefix.length(),
                targetFileName.length() - ".jar".length());
        return version.endsWith(RUNNER_SUFFIX)
                ? version.substring(0, version.length() - RUNNER_SUFFIX.length())
                : version;
    }

    private String expand(String template) {
        return template
                .replace("${user.home}", System.getProperty("user.home"))
                .replace("${user.dir}", System.getProperty("user.dir"));
    }
}
