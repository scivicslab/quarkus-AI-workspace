package com.scivicslab.aiworkspace.config;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * The names a catalog entry's files take in {@code ~/works} and in the build directory, so that two entries of one
 * repository that build different branches never share a file (DashboardBuilder_261118_oo01).
 *
 * <p>An entry of the default branch keeps its names. An entry with a {@code branch} adds {@code @<short branch>} to
 * each: its link ({@code chat-ui-with-audit-trail@v5.x.jar}), its companion links, its versioned files and its build
 * checkout. The separator is {@code @} because {@code -} followed by a digit already marks a version.</p>
 *
 * <p>Every versioned file Build Snapshot places carries the time the build finished, to the second
 * ({@code -YYMMDDHHmmss}), so a rebuild never overwrites a file a running JVM has open. Files placed before carry
 * ten digits ({@code -YYMMDDHHmm}); both are read as a stamp.</p>
 */
public final class BranchVariant {

    /** The separator between a name and its short branch name. */
    public static final String SEPARATOR = "@";

    /** The stamp Build Snapshot adds to a versioned file: the time the build finished, to the second. */
    public static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyMMddHHmmss");

    /** A stamp at the end of a name: ten digits (to the minute, placed by hand) or twelve (to the second). */
    private static final Pattern TRAILING_STAMP = Pattern.compile("-(\\d{12}|\\d{10})$");

    private BranchVariant() {}

    /**
     * @param branch a branch name, or null or blank for the default branch
     * @return the branch with {@code /} replaced by {@code -} ({@code feat/x} gives {@code feat-x}), or {@code ""} for the
     *         default branch
     */
    public static String shortName(String branch) {
        return branch == null || branch.isBlank() ? "" : branch.strip().replace('/', '-');
    }

    /** @return what a name of {@code branch} ends with before {@code .jar}: {@code @<short branch>}, or {@code ""} */
    public static String suffix(String branch) {
        String s = shortName(branch);
        return s.isEmpty() ? "" : SEPARATOR + s;
    }

    /**
     * @param defaultLink the link name of the default branch's entry, for example {@code chat-ui-with-audit-trail.jar}
     * @return the link name of the same jar built from {@code branch}: {@code chat-ui-with-audit-trail@v5.x.jar}; the
     *         default link unchanged for the default branch
     */
    public static String linkName(String defaultLink, String branch) {
        return stripJar(defaultLink) + suffix(branch) + ".jar";
    }

    /**
     * @param linkName a link name, with or without a branch
     * @return the base the build's output is found by: the name without {@code .jar} and without {@code @...}
     *         ({@code chat-ui-with-audit-trail@v5.x.jar} gives {@code chat-ui-with-audit-trail})
     */
    public static String baseOf(String linkName) {
        if (linkName == null) return null;
        String base = stripJar(linkName);
        int at = base.indexOf(SEPARATOR);
        return at < 0 ? base : base.substring(0, at);
    }

    /**
     * The name Build Snapshot gives the jar it places in {@code ~/works}.
     *
     * @param builtFileName the file name the build gave it, for example {@code chat-ui-with-audit-trail-3.0.0-SNAPSHOT.jar}
     * @param stamp         the time the build finished, as {@link #STAMP} writes it
     * @param branch        the entry's branch, or null for the default branch
     * @return for example {@code chat-ui-with-audit-trail-3.0.0-SNAPSHOT-261118230512@v5.x.jar}
     */
    public static String versionedName(String builtFileName, String stamp, String branch) {
        return stripJar(builtFileName) + "-" + stamp + suffix(branch) + ".jar";
    }

    /** @return the stamp for a build that finished at {@code at} */
    public static String stamp(LocalDateTime at) {
        return STAMP.format(at);
    }

    /**
     * @param leaf   the repository's name, the leaf of {@code owner/repo}
     * @param branch the entry's branch, or null for the default branch
     * @return the directory name of the build checkout: {@code <repo>} or {@code <repo>@<short branch>}
     */
    public static String checkoutDir(String leaf, String branch) {
        return leaf + suffix(branch);
    }

    /**
     * @param versionedBase a versioned file name without {@code .jar}
     * @return it without a trailing {@code @<short branch>} and without a trailing ten- or twelve-digit stamp
     */
    public static String withoutBranchAndStamp(String versionedBase) {
        String s = versionedBase;
        int at = s.indexOf(SEPARATOR);
        if (at >= 0) s = s.substring(0, at);
        return TRAILING_STAMP.matcher(s).replaceFirst("");
    }

    private static String stripJar(String name) {
        return name.endsWith(".jar") ? name.substring(0, name.length() - ".jar".length()) : name;
    }
}
