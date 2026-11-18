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
}
