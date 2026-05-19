package com.scivicslab.aiworkspace.config;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Loads AI workspace configuration from the built-in classpath resource
 * {@code ai-workspace-default.yaml}.
 *
 * <p>No external config files are read. The only runtime override supported is
 * {@code -Dservice.portal.access.host=<host>} to set the hostname used in
 * dashboard tool links.
 */
public class AiWorkspaceConfigLoader {

    private static final Logger logger = Logger.getLogger(AiWorkspaceConfigLoader.class.getName());

    private static String lastLoadedPath = "(built-in default)";

    /** Returns the path of the config loaded most recently. */
    public static String getLastLoadedPath() {
        return lastLoadedPath;
    }

    public static AiWorkspaceConfig load() {
        try (InputStream in = AiWorkspaceConfigLoader.class.getClassLoader()
                .getResourceAsStream("ai-workspace-default.yaml")) {
            if (in != null) {
                return parse(in);
            }
        } catch (Exception e) {
            logger.warning("Failed to load built-in default config: " + e.getMessage());
        }

        logger.warning("Built-in default config not found — using empty defaults");
        return AiWorkspaceConfig.defaultConfig();
    }

    @SuppressWarnings("unchecked")
    private static AiWorkspaceConfig parse(InputStream in) {
        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(in);

        String backend    = (String) root.getOrDefault("backend", "jvm");
        String accessHost = (String) root.get("accessHost");

        // Runtime override for dashboard tool links
        String propHost = System.getProperty("service.portal.access.host");
        if (propHost != null && !propHost.isBlank()) accessHost = propHost;

        AiWorkspaceConfig.JvmConfig jvmConfig = null;
        if (root.containsKey("jvm")) {
            Map<String, Object> jvm = (Map<String, Object>) root.get("jvm");
            List<Map<String, Object>> tools = (List<Map<String, Object>>) jvm.get("tools");

            List<AiWorkspaceConfig.ToolDefinition> toolDefs = tools.stream()
                .map(t -> {
                    @SuppressWarnings("unchecked")
                    List<String> args = (List<String>) t.get("args");
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> rawParams = (List<Map<String, Object>>) t.get("params");
                    List<AiWorkspaceConfig.ParamDefinition> params = rawParams == null ? List.of()
                        : rawParams.stream().map(p -> {
                            @SuppressWarnings("unchecked")
                            List<Map<String, String>> rawOpts = (List<Map<String, String>>) p.get("options");
                            List<AiWorkspaceConfig.ParamOption> opts = rawOpts == null ? List.of()
                                : rawOpts.stream()
                                    .map(o -> new AiWorkspaceConfig.ParamOption(
                                        (String) o.get("value"), (String) o.get("label")))
                                    .toList();
                            return new AiWorkspaceConfig.ParamDefinition(
                                (String) p.get("key"),
                                (String) p.getOrDefault("label", p.get("key")),
                                (String) p.getOrDefault("type", "text"),
                                (String) p.getOrDefault("default", ""),
                                (String) p.get("jvmProp"),
                                (Boolean) p.getOrDefault("workingDir", false),
                                p.containsKey("argPos") ? ((Number) p.get("argPos")).intValue() : -1,
                                opts
                            );
                        }).toList();
                    return new AiWorkspaceConfig.ToolDefinition(
                        (String) t.get("name"),
                        (String) t.get("jar"),
                        (Integer) t.get("port"),
                        (Boolean) t.getOrDefault("autoStart", false),
                        (Boolean) t.getOrDefault("fixedPort", false),
                        (Boolean) t.getOrDefault("singleInstance", false),
                        args,
                        params,
                        (String) t.get("gatewayMcpProp"),
                        (String) t.get("github")
                    );
                })
                .toList();

            jvmConfig = new AiWorkspaceConfig.JvmConfig(toolDefs);
        }

        return new AiWorkspaceConfig(backend, accessHost, jvmConfig);
    }
}
