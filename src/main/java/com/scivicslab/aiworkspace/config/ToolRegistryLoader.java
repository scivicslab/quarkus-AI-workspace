package com.scivicslab.aiworkspace.config;

import com.scivicslab.aiworkspace.spi.WorkspaceToolPlugin;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.yaml.snakeyaml.Yaml;

/**
 * Loads the tool registry from ai-workspace-tools.yaml bundled in the uber-JAR.
 * Falls back to BootstrapPlugins if the YAML is missing or unparseable.
 */
public final class ToolRegistryLoader {

    private static final Logger logger = Logger.getLogger(ToolRegistryLoader.class.getName());

    private ToolRegistryLoader() {}

    public static List<ToolRegistryEntry> load() {
        try (InputStream is = ToolRegistryLoader.class.getResourceAsStream("/ai-workspace-tools.yaml")) {
            if (is == null) {
                logger.warning("ai-workspace-tools.yaml not found — falling back to BootstrapPlugins");
                return fallback();
            }
            return parse(is);
        } catch (Exception e) {
            logger.warning("Failed to load ai-workspace-tools.yaml: " + e.getMessage() + " — falling back to BootstrapPlugins");
            return fallback();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ToolRegistryEntry> parse(InputStream is) {
        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(is);
        // Use Map<String, Object>: 'library: true' is parsed as Boolean by SnakeYAML, not String.
        List<Map<String, Object>> tools = (List<Map<String, Object>>) root.get("tools");
        if (tools == null) return List.of();
        List<ToolRegistryEntry> result = new ArrayList<>();
        for (Map<String, Object> entry : tools) {
            String name    = str(entry.get("name"));
            String jar     = str(entry.get("jar"));
            String github  = str(entry.get("github"));
            boolean library = Boolean.TRUE.equals(entry.get("library"));
            // Library entries (library: true) have no jar — they install to ~/.m2 only.
            if (name != null && (jar != null || library)) {
                result.add(new ToolRegistryEntry(name, jar, github, library));
            }
        }
        logger.info("Loaded tool registry: " + result.size() + " entries from ai-workspace-tools.yaml");
        return List.copyOf(result);
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static List<ToolRegistryEntry> fallback() {
        return BootstrapPlugins.all().stream()
            .map(p -> new ToolRegistryEntry(p.toolName(), p.jarFileName(), p.githubRepo(), false))
            .toList();
    }
}
