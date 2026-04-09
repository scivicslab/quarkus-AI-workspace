package com.scivicslab.serviceportal.config;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Loads service-portal configuration.
 *
 * <p>Search order:
 * <ol>
 *   <li>{@code -Dservice.portal.config=<path>} — explicit file path (highest priority)</li>
 *   <li>{@code -Dservice.portal.backend=<mode>} — mode-specific file
 *       {@code service-portal-<mode>.yaml} in cwd, then {@code /app/}</li>
 *   <li>{@code service-portal.yaml} in cwd</li>
 *   <li>{@code /app/service-portal.yaml}</li>
 *   <li>Classpath {@code service-portal.yaml}</li>
 * </ol>
 *
 * <p>Valid backend modes: {@code jvm}, {@code multi-docker}, {@code lxd}
 */
public class ServicePortalConfigLoader {

    private static final Logger logger = Logger.getLogger(ServicePortalConfigLoader.class.getName());
    private static final String CONFIG_FILE = "service-portal.yaml";

    public static ServicePortalConfig load() {
        // 1. Explicit path override
        String configPathProp = System.getProperty("service.portal.config");
        if (configPathProp != null) {
            Path p = Path.of(configPathProp);
            if (Files.exists(p)) {
                return loadFromFile(p);
            }
            logger.warning("Config path from system property not found: " + configPathProp);
        }

        // 2. Backend mode → service-portal-<mode>.yaml
        String backend = System.getProperty("service.portal.backend");
        if (backend != null && !backend.isBlank()) {
            String modeFile = "service-portal-" + backend.trim() + ".yaml";
            Path local = Path.of(modeFile);
            if (Files.exists(local)) {
                return loadFromFile(local);
            }
            Path container = Path.of("/app/" + modeFile);
            if (Files.exists(container)) {
                return loadFromFile(container);
            }
            logger.warning("Backend mode '" + backend + "' specified but " + modeFile
                + " not found in cwd or /app/ — falling through to generic config");
        }

        // 3. Generic service-portal.yaml in cwd
        Path localConfig = Path.of(CONFIG_FILE);
        if (Files.exists(localConfig)) {
            return loadFromFile(localConfig);
        }

        // 4. Standard container path
        Path containerConfig = Path.of("/app/" + CONFIG_FILE);
        if (Files.exists(containerConfig)) {
            return loadFromFile(containerConfig);
        }

        // 5. Classpath fallback
        try (InputStream in = ServicePortalConfigLoader.class.getClassLoader()
                .getResourceAsStream(CONFIG_FILE)) {
            if (in != null) {
                logger.info("Loading config from classpath");
                return parse(in);
            }
        } catch (Exception e) {
            logger.warning("Failed to load classpath config: " + e.getMessage());
        }

        logger.info("No config found, using defaults");
        return ServicePortalConfig.defaultConfig();
    }

    private static ServicePortalConfig loadFromFile(Path path) {
        logger.info("Loading config from: " + path.toAbsolutePath());
        try (InputStream in = Files.newInputStream(path)) {
            return parse(in);
        } catch (Exception e) {
            logger.warning("Failed to load config from " + path + ": " + e.getMessage());
            return ServicePortalConfig.defaultConfig();
        }
    }

    @SuppressWarnings("unchecked")
    private static ServicePortalConfig parse(InputStream in) {
        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(in);

        String backend = (String) root.getOrDefault("backend", "auto");

        ServicePortalConfig.DockerConfig dockerConfig = null;
        if (root.containsKey("jvm")) {
            Map<String, Object> docker = (Map<String, Object>) root.get("jvm");
            List<Map<String, Object>> tools = (List<Map<String, Object>>) docker.get("tools");

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
                        args,
                        params
                    );
                })
                .toList();

            dockerConfig = new ServicePortalConfig.DockerConfig(toolDefs);
        }

        ServicePortalConfig.MultiDockerConfig multiDockerConfig = null;
        if (root.containsKey("multi-docker")) {
            Map<String, Object> md = (Map<String, Object>) root.get("multi-docker");
            multiDockerConfig = new ServicePortalConfig.MultiDockerConfig(
                (String) md.get("image"),
                (String) md.get("vllmEndpoint"),
                (String) md.get("defaultWorkdir")
            );
        }

        ServicePortalConfig.LxdConfig lxdConfig = null;
        if (root.containsKey("lxd")) {
            Map<String, Object> lxd = (Map<String, Object>) root.get("lxd");

            List<ServicePortalConfig.ManagementService> management = List.of();
            if (lxd.containsKey("management")) {
                List<Map<String, Object>> mgmt = (List<Map<String, Object>>) lxd.get("management");
                management = mgmt.stream()
                    .map(m -> new ServicePortalConfig.ManagementService(
                        (String) m.get("unit"),
                        (Integer) m.get("port")
                    ))
                    .toList();
            }

            List<ServicePortalConfig.ContainerConfig> containers = List.of();
            if (lxd.containsKey("containers")) {
                List<Map<String, Object>> cnts = (List<Map<String, Object>>) lxd.get("containers");
                containers = cnts.stream()
                    .map(c -> new ServicePortalConfig.ContainerConfig(
                        (String) c.get("name"),
                        (String) c.get("template")
                    ))
                    .toList();
            }

            lxdConfig = new ServicePortalConfig.LxdConfig(management, containers);
        }

        return new ServicePortalConfig(backend, dockerConfig, multiDockerConfig, lxdConfig);
    }
}
