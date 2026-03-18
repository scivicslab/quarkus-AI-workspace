package com.scivicslab.lxdpups.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import io.quarkus.runtime.StartupEvent;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Loads portal.yaml on startup.
 * Priority: ./portal.yaml (CWD) > classpath portal.yaml
 */
@ApplicationScoped
public class PortalConfigLoader {

    private static final Logger LOG = Logger.getLogger(PortalConfigLoader.class.getName());
    private PortalConfig config = new PortalConfig();

    void onStartup(@Observes StartupEvent event) {
        load();
    }

    public void load() {
        var yaml = new Yaml();
        Map<String, Object> root = null;

        // Try external file first
        Path externalPath = Path.of("portal.yaml");
        if (Files.exists(externalPath)) {
            try (InputStream is = Files.newInputStream(externalPath)) {
                root = yaml.load(is);
                LOG.info("Loaded portal.yaml from " + externalPath.toAbsolutePath());
            } catch (Exception e) {
                LOG.warning("Failed to load external portal.yaml: " + e.getMessage());
            }
        }

        // Fallback to classpath
        if (root == null) {
            try (InputStream is = getClass().getClassLoader().getResourceAsStream("portal.yaml")) {
                if (is != null) {
                    root = yaml.load(is);
                    LOG.info("Loaded portal.yaml from classpath");
                }
            } catch (Exception e) {
                LOG.warning("Failed to load classpath portal.yaml: " + e.getMessage());
            }
        }

        if (root == null) {
            LOG.info("No portal.yaml found, using defaults");
            return;
        }

        config = parse(root);
    }

    public PortalConfig getConfig() {
        return config;
    }

    @SuppressWarnings("unchecked")
    PortalConfig parse(Map<String, Object> root) {
        var cfg = new PortalConfig();

        // Portal section
        if (root.get("portal") instanceof Map<?, ?> portal) {
            if (portal.get("title") instanceof String title) cfg.setTitle(title);
            if (portal.get("port") instanceof Integer port) cfg.setPort(port);
            if (portal.get("mode") instanceof String mode) cfg.setMode(mode);
        }

        // Management services
        if (root.get("management") instanceof Map<?, ?> mgmt) {
            var services = new ArrayList<PortalConfig.ManagementService>();
            for (var entry : ((Map<String, Object>) mgmt).entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> svcMap) {
                    var svc = parseManagementService(entry.getKey(), (Map<String, Object>) svcMap);
                    services.add(svc);
                }
            }
            cfg.setManagementServices(services);
        }

        // Worker template
        if (root.get("worker-template") instanceof Map<?, ?> tmpl) {
            var services = new ArrayList<PortalConfig.WorkerService>();
            for (var entry : ((Map<String, Object>) tmpl).entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> svcMap) {
                    var svc = parseWorkerService(entry.getKey(), (Map<String, Object>) svcMap);
                    services.add(svc);
                }
            }
            cfg.setWorkerTemplate(services);
        }

        // Remotes
        if (root.get("remotes") instanceof Map<?, ?> remotes) {
            var list = new ArrayList<PortalConfig.Remote>();
            for (var entry : ((Map<String, Object>) remotes).entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> rMap) {
                    var r = new PortalConfig.Remote();
                    r.setName(entry.getKey());
                    if (rMap.get("address") instanceof String addr) r.setAddress(addr);
                    if (rMap.get("description") instanceof String desc) r.setDescription(desc);
                    list.add(r);
                }
            }
            cfg.setRemotes(list);
        }

        return cfg;
    }

    @SuppressWarnings("unchecked")
    private PortalConfig.ManagementService parseManagementService(String name, Map<String, Object> map) {
        var svc = new PortalConfig.ManagementService();
        svc.setName(name);
        if (map.get("unit") instanceof String unit) svc.setUnit(unit);
        if (map.get("port") instanceof Integer port) svc.setPort(port);
        if (map.get("description") instanceof String desc) svc.setDescription(desc);
        if (map.get("ui") instanceof String ui) svc.setUi(ui);
        if (map.get("enabled") instanceof Boolean enabled) svc.setEnabled(enabled);
        if (map.get("binary") instanceof Map<?, ?> binMap) {
            svc.setBinary(parseBinary((Map<String, Object>) binMap));
        }
        return svc;
    }

    private PortalConfig.ManagementService.Binary parseBinary(Map<String, Object> map) {
        var bin = new PortalConfig.ManagementService.Binary();
        if (map.get("repo") instanceof String repo) bin.setRepo(repo);
        if (map.get("version") instanceof String version) bin.setVersion(version);
        if (map.get("asset") instanceof String asset) bin.setAsset(asset);
        if (map.get("path") instanceof String path) bin.setPath(path);
        if (map.get("runtime") instanceof String runtime) bin.setRuntime(runtime);
        if (map.get("args") instanceof String args) bin.setArgs(args);
        return bin;
    }

    @SuppressWarnings("unchecked")
    private PortalConfig.WorkerService parseWorkerService(String name, Map<String, Object> map) {
        var svc = new PortalConfig.WorkerService();
        svc.setName(name);
        if (map.get("unit") instanceof String unit) svc.setUnit(unit);
        if (map.get("singleton") instanceof Boolean singleton) svc.setSingleton(singleton);
        if (map.get("port") instanceof Integer port) svc.setPort(port);
        if (map.get("port-range") instanceof String range) svc.setPortRange(range);
        if (map.get("description") instanceof String desc) svc.setDescription(desc);
        if (map.get("enabled") instanceof Boolean enabled) svc.setEnabled(enabled);
        if (map.get("instances") instanceof List<?> instances) {
            var list = new ArrayList<PortalConfig.WorkerService.Instance>();
            for (var item : instances) {
                if (item instanceof Map<?, ?> iMap) {
                    var inst = new PortalConfig.WorkerService.Instance();
                    if (iMap.get("port") instanceof Integer p) inst.setPort(p);
                    if (iMap.get("title") instanceof String t) inst.setTitle(t);
                    list.add(inst);
                }
            }
            svc.setInstances(list);
        }
        return svc;
    }
}
