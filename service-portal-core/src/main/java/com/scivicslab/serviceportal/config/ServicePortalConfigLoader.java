package com.scivicslab.serviceportal.config;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Loads service-portal.yaml configuration.
 */
public class ServicePortalConfigLoader {

    private static final Logger logger = Logger.getLogger(ServicePortalConfigLoader.class.getName());
    private static final String CONFIG_FILE = "service-portal.yaml";

    /**
     * Load configuration from service-portal.yaml.
     * Looks in current directory first, then classpath.
     */
    public static ServicePortalConfig load() {
        Path localConfig = Path.of(CONFIG_FILE);

        if (Files.exists(localConfig)) {
            logger.info("Loading config from: " + localConfig.toAbsolutePath());
            try (InputStream in = Files.newInputStream(localConfig)) {
                return parse(in);
            } catch (Exception e) {
                logger.warning("Failed to load local config: " + e.getMessage());
            }
        }

        // Fallback to classpath
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

    @SuppressWarnings("unchecked")
    private static ServicePortalConfig parse(InputStream in) {
        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(in);

        String backend = (String) root.getOrDefault("backend", "auto");

        ServicePortalConfig.DockerConfig dockerConfig = null;
        if (root.containsKey("docker")) {
            Map<String, Object> docker = (Map<String, Object>) root.get("docker");
            List<Map<String, Object>> tools = (List<Map<String, Object>>) docker.get("tools");

            List<ServicePortalConfig.ToolDefinition> toolDefs = tools.stream()
                .map(t -> new ServicePortalConfig.ToolDefinition(
                    (String) t.get("name"),
                    (String) t.get("jar"),
                    (Integer) t.get("port"),
                    (Boolean) t.getOrDefault("autoStart", false)
                ))
                .toList();

            dockerConfig = new ServicePortalConfig.DockerConfig(toolDefs);
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

        return new ServicePortalConfig(backend, dockerConfig, lxdConfig);
    }
}
