package com.scivicslab.aiworkspace.config;

import com.scivicslab.aiworkspace.spi.WorkspaceToolPlugin;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.logging.Logger;

/**
 * Loads the acquired tool plugin list by merging the tool registry with JAR-discovered
 * WorkspaceToolPlugin implementations.
 *
 * Resolution order for each registry entry:
 *   1. WorkspaceToolPlugin found in ~/works/ JAR  → use it (full metadata from tool)
 *   2. JAR exists in ~/works/ but no plugin        → fall back to BootstrapPlugins
 *   3. JAR not in ~/works/                         → skip (caller treats as not-acquired)
 */
public final class PluginLoader {

    private static final Logger logger = Logger.getLogger(PluginLoader.class.getName());

    private PluginLoader() {}

    /**
     * Returns only the acquired plugins (tools whose JAR is present in ~/works/).
     * Tools in the registry without a corresponding JAR are excluded; the caller is
     * responsible for rendering those as "not acquired" tiles.
     */
    public static List<WorkspaceToolPlugin> loadAll(List<ToolRegistryEntry> registry) {
        Map<String, WorkspaceToolPlugin> fromJars = new LinkedHashMap<>();
        for (WorkspaceToolPlugin p : discoverFromJars()) {
            fromJars.put(p.toolName(), p);
        }

        Map<String, WorkspaceToolPlugin> bootstrapMap = new LinkedHashMap<>();
        for (WorkspaceToolPlugin p : BootstrapPlugins.all()) {
            bootstrapMap.put(p.toolName(), p);
        }

        Path worksDir = Path.of(System.getProperty("user.home"), "works");
        Map<String, WorkspaceToolPlugin> result = new LinkedHashMap<>();

        for (ToolRegistryEntry entry : registry) {
            if (entry.library()) {
                continue;   // library entries install to ~/.m2; no jar in ~/works/ to load
            }
            WorkspaceToolPlugin fromJar = fromJars.get(entry.name());
            if (fromJar != null) {
                logger.info("Using WorkspaceToolPlugin from JAR for: " + entry.name());
                result.put(entry.name(), fromJar);
            } else if (Files.exists(worksDir.resolve(entry.jarFileName()))) {
                WorkspaceToolPlugin bootstrap = bootstrapMap.get(entry.name());
                if (bootstrap != null) {
                    logger.info("JAR present but no WorkspaceToolPlugin — using BootstrapPlugins for: " + entry.name());
                    result.put(entry.name(), bootstrap);
                } else {
                    logger.info("JAR present but no metadata available for: " + entry.name() + " — skipping");
                }
            } else {
                logger.info("JAR not present (not acquired): " + entry.name());
            }
        }

        // Also include tools found in JARs that are not in the registry (forward compat)
        for (WorkspaceToolPlugin p : fromJars.values()) {
            result.putIfAbsent(p.toolName(), p);
        }

        return List.copyOf(result.values());
    }

    /**
     * Scans ~/works/ for JARs containing WorkspaceToolPlugin implementations.
     * Symlinks are resolved to avoid scanning the same physical JAR twice.
     */
    static List<WorkspaceToolPlugin> discoverFromJars() {
        Path worksDir = Path.of(System.getProperty("user.home"), "works");
        if (!Files.isDirectory(worksDir)) return List.of();

        File[] jars = worksDir.toFile().listFiles(
            f -> f.getName().endsWith(".jar") && !f.getName().endsWith(".tmp"));
        if (jars == null) return List.of();

        List<WorkspaceToolPlugin> found = new ArrayList<>();
        Map<Path, Boolean> scanned = new LinkedHashMap<>();

        for (File jar : jars) {
            Path real;
            try {
                real = jar.toPath().toRealPath();
            } catch (Exception e) {
                real = jar.toPath().toAbsolutePath();
            }
            if (scanned.putIfAbsent(real, Boolean.TRUE) != null) {
                continue;
            }
            try {
                URL[] urls = { real.toUri().toURL() };
                URLClassLoader cl = new URLClassLoader(urls, PluginLoader.class.getClassLoader());
                ServiceLoader<WorkspaceToolPlugin> loader =
                    ServiceLoader.load(WorkspaceToolPlugin.class, cl);
                for (WorkspaceToolPlugin plugin : loader) {
                    found.add(plugin);
                    logger.info("Loaded WorkspaceToolPlugin from " + jar.getName() + ": " + plugin.toolName());
                }
            } catch (Exception e) {
                // Most JARs will not contain a WorkspaceToolPlugin — silently skip.
            }
        }
        return found;
    }
}
