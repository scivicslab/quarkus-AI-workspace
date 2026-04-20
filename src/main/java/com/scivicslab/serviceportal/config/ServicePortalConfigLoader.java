package com.scivicslab.serviceportal.config;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Loads service-portal configuration from the built-in classpath resource
 * {@code service-portal-default.yaml}.
 *
 * <p>No external config files are read. The only runtime override supported is
 * {@code -Dservice.portal.access.host=<host>} to set the hostname used in
 * dashboard tool links.
 */
public class ServicePortalConfigLoader {

    private static final Logger logger = Logger.getLogger(ServicePortalConfigLoader.class.getName());

    private static String lastLoadedPath = "(built-in default)";

    /** Returns the path of the config loaded most recently. */
    public static String getLastLoadedPath() {
        return lastLoadedPath;
    }

    public static ServicePortalConfig load() {
        try (InputStream in = ServicePortalConfigLoader.class.getClassLoader()
                .getResourceAsStream("service-portal-default.yaml")) {
            if (in != null) {
                return parse(in);
            }
        } catch (Exception e) {
            logger.warning("Failed to load built-in default config: " + e.getMessage());
        }

        logger.warning("Built-in default config not found — using empty defaults");
        return ServicePortalConfig.defaultConfig();
    }

    @SuppressWarnings("unchecked")
    private static ServicePortalConfig parse(InputStream in) {
        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(in);

        String backend    = (String) root.getOrDefault("backend", "jvm");
        String accessHost = (String) root.get("accessHost");

        // Runtime override for dashboard tool links
        String propHost = System.getProperty("service.portal.access.host");
        if (propHost != null && !propHost.isBlank()) accessHost = propHost;

        ServicePortalConfig.JvmConfig jvmConfig = null;
        if (root.containsKey("jvm")) {
            Map<String, Object> jvm = (Map<String, Object>) root.get("jvm");
            List<Map<String, Object>> tools = (List<Map<String, Object>>) jvm.get("tools");

            List<ServicePortalConfig.ToolDefinition> toolDefs = tools.stream()
                .map(t -> {
                    @SuppressWarnings("unchecked")
                    List<String> args = (List<String>) t.get("args");
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> rawParams = (List<Map<String, Object>>) t.get("params");
                    List<ServicePortalConfig.ParamDefinition> params = rawParams == null ? List.of()
                        : rawParams.stream().map(p -> {
                            @SuppressWarnings("unchecked")
                            List<Map<String, String>> rawOpts = (List<Map<String, String>>) p.get("options");
                            List<ServicePortalConfig.ParamOption> opts = rawOpts == null ? List.of()
                                : rawOpts.stream()
                                    .map(o -> new ServicePortalConfig.ParamOption(
                                        (String) o.get("value"), (String) o.get("label")))
                                    .toList();
                            return new ServicePortalConfig.ParamDefinition(
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
                    return new ServicePortalConfig.ToolDefinition(
                        (String) t.get("name"),
                        (String) t.get("jar"),
                        (Integer) t.get("port"),
                        (Boolean) t.getOrDefault("autoStart", false),
                        (Boolean) t.getOrDefault("fixedPort", false),
                        args,
                        params,
                        (String) t.get("gatewayMcpProp")
                    );
                })
                .toList();

            jvmConfig = new ServicePortalConfig.JvmConfig(toolDefs);
        }

        return new ServicePortalConfig(backend, accessHost, jvmConfig);
    }
}
