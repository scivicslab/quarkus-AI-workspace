package com.scivicslab.aiworkspace.config;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.yaml.snakeyaml.Yaml;

/**
 * Loads the tool registry from ai-workspace-tools.yaml bundled in the uber-JAR.
 *
 * <p>The bundled YAML is the single source of truth for tool launch metadata. If it is missing
 * or unparseable the registry is empty (no tools appear) — there is no hardcoded fallback, so
 * the YAML cannot be silently shadowed by stale in-code definitions.</p>
 */
public final class ToolRegistryLoader {

    private static final Logger logger = Logger.getLogger(ToolRegistryLoader.class.getName());

    private ToolRegistryLoader() {}

    public static List<ToolRegistryEntry> load() {
        try (InputStream is = ToolRegistryLoader.class.getResourceAsStream("/ai-workspace-tools.yaml")) {
            if (is == null) {
                logger.warning("ai-workspace-tools.yaml not found on classpath — registry is empty");
                return List.of();
            }
            return parse(is);
        } catch (Exception e) {
            logger.warning("Failed to load ai-workspace-tools.yaml: " + e.getMessage() + " — registry is empty");
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ToolRegistryEntry> parse(InputStream is) {
        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(is);
        List<Map<String, Object>> tools = (List<Map<String, Object>>) root.get("tools");
        if (tools == null) return List.of();
        List<ToolRegistryEntry> result = new ArrayList<>();
        for (Map<String, Object> entry : tools) {
            String name    = str(entry.get("name"));
            String jar     = str(entry.get("jar"));
            String github  = str(entry.get("github"));
            boolean library = bool(entry.get("library"), false);
            // A valid entry needs a name, and either a jar (deployable tool) or library:true.
            if (name == null || (jar == null && !library)) continue;
            result.add(new ToolRegistryEntry(
                name, jar, github, library,
                intOf(entry.get("port"), 0),
                bool(entry.get("autoStart"), false),
                bool(entry.get("fixedPort"), false),
                bool(entry.get("singleInstance"), false),
                strList(entry.get("args")),
                strList(entry.get("jvmArgs")),
                parseParams(entry.get("params")),
                str(entry.get("gatewayMcpProp"))
            ));
        }
        logger.info("Loaded tool registry: " + result.size() + " entries from ai-workspace-tools.yaml");
        return List.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private static List<AiWorkspaceConfig.ParamDefinition> parseParams(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<AiWorkspaceConfig.ParamDefinition> params = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> p = (Map<String, Object>) item;
            List<AiWorkspaceConfig.ParamOption> options = new ArrayList<>();
            if (p.get("options") instanceof List<?> opts) {
                for (Object o : opts) {
                    if (o instanceof Map) {
                        Map<String, Object> om = (Map<String, Object>) o;
                        options.add(new AiWorkspaceConfig.ParamOption(str(om.get("value")), str(om.get("label"))));
                    }
                }
            }
            params.add(new AiWorkspaceConfig.ParamDefinition(
                str(p.get("key")),
                str(p.get("label")),
                str(p.get("type")),
                p.get("default") == null ? "" : str(p.get("default")),
                str(p.get("jvmProp")),
                bool(p.get("workingDir"), false),
                intOf(p.get("argPos"), -1),
                List.copyOf(options)
            ));
        }
        return List.copyOf(params);
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static boolean bool(Object v, boolean def) {
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        return "true".equalsIgnoreCase(v.toString());
    }

    private static int intOf(Object v, int def) {
        if (v == null) return def;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Object v) {
        if (!(v instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (o != null) out.add(o.toString());
        }
        return List.copyOf(out);
    }
}
