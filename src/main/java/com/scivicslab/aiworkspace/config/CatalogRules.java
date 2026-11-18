package com.scivicslab.aiworkspace.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The two rules a catalog must keep so that installing one entry never replaces another entry's jar
 * (DashboardBuilder_261118_oo01).
 *
 * <ul>
 * <li>An entry with a {@code branch} names its link and each companion link {@code <base>@<short branch>.jar}.</li>
 * <li>Two entries that install from different places (a different repository or branch) never share a link name.
 *     Entries that differ only in how they are started may share one.</li>
 * </ul>
 */
public final class CatalogRules {

    private CatalogRules() {}

    /** @return one sentence per broken rule; empty when the catalog keeps both */
    public static List<String> violations(List<ToolRegistryEntry> catalog) {
        List<String> out = new ArrayList<>();
        Map<String, ToolRegistryEntry> owner = new LinkedHashMap<>();
        for (ToolRegistryEntry e : catalog) {
            if (e.library()) continue;
            if (e.link()) {
                linkViolations(e, out);
                continue;
            }
            List<String> links = new ArrayList<>();
            if (e.jarFileName() != null) links.add(e.jarFileName());
            links.addAll(e.companionJars());
            String suffix = BranchVariant.suffix(e.branch()) + ".jar";
            for (String link : links) {
                if (!BranchVariant.suffix(e.branch()).isEmpty() && !link.endsWith(suffix)) {
                    out.add(e.name() + ": builds branch " + e.branch() + ", so its link " + link + " must end with " + suffix);
                }
                ToolRegistryEntry other = owner.putIfAbsent(link, e);
                if (other != null && !(Objects.equals(other.githubRepo(), e.githubRepo())
                        && Objects.equals(BranchVariant.shortName(other.branch()), BranchVariant.shortName(e.branch())))) {
                    out.add(e.name() + " and " + other.name() + " install from different places but share the link " + link);
                }
            }
        }
        return out;
    }

    /**
     * A link entry has a {@code url} and nothing a launched tool has: it starts, stops and builds nothing, so a
     * {@code jar}, a repository or launch arguments on it would be read by nobody, or worse, by something that starts
     * a process (DashboardBuilder_261118_oo01).
     */
    private static void linkViolations(ToolRegistryEntry e, List<String> out) {
        if (e.url() == null || e.url().isBlank()) out.add(e.name() + ": a link entry needs a url");
        List<String> launchFields = new ArrayList<>();
        if (e.jarFileName() != null) launchFields.add("jar");
        if (e.githubRepo() != null) launchFields.add("github");
        if (e.branch() != null) launchFields.add("branch");
        if (e.artifact() != null) launchFields.add("artifact");
        if (!e.companionJars().isEmpty()) launchFields.add("companionJars");
        if (!e.dependsOn().isEmpty()) launchFields.add("dependsOn");
        if (!e.args().isEmpty()) launchFields.add("args");
        if (!e.jvmArgs().isEmpty()) launchFields.add("jvmArgs");
        if (!e.params().isEmpty()) launchFields.add("params");
        if (!launchFields.isEmpty()) {
            out.add(e.name() + ": a link entry starts nothing, so it cannot have " + String.join(", ", launchFields));
        }
    }
}
