package com.scivicslab.lxdpups.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PortalConfigLoaderTest {

    private final PortalConfigLoader loader = new PortalConfigLoader();

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseYaml(String yaml) {
        return new Yaml().load(yaml);
    }

    @Test
    void parsePortalSection() {
        var root = parseYaml("""
                portal:
                  title: "Test Portal"
                  port: 9090
                """);
        var config = loader.parse(root);
        assertEquals("Test Portal", config.getTitle());
        assertEquals(9090, config.getPort());
    }

    @Test
    void parseManagementServices() {
        var root = parseYaml("""
                management:
                  mcp-gateway:
                    enabled: true
                    unit: mcp-gateway.service
                    port: 8888
                    description: "MCP Gateway"
                    ui: "http://localhost:8888/"
                  predict-ja:
                    enabled: false
                    unit: predict-ja.service
                    port: 8190
                    description: "predict-ja"
                """);
        var config = loader.parse(root);
        assertEquals(2, config.getManagementServices().size());

        var gateway = config.getManagementServices().stream()
                .filter(s -> "mcp-gateway".equals(s.getName())).findFirst().orElseThrow();
        assertEquals("mcp-gateway.service", gateway.getUnit());
        assertEquals(8888, gateway.getPort());
        assertTrue(gateway.isEnabled());
        assertEquals("http://localhost:8888/", gateway.getUi());

        var predictJa = config.getManagementServices().stream()
                .filter(s -> "predict-ja".equals(s.getName())).findFirst().orElseThrow();
        assertFalse(predictJa.isEnabled());
    }

    @Test
    void parseWorkerTemplate() {
        var root = parseYaml("""
                worker-template:
                  llm-console-claude:
                    enabled: true
                    singleton: false
                    unit: llm-console-claude@.service
                    port-range: 8200-8299
                    instances:
                      - port: 8200
                        title: "Coding"
                      - port: 8201
                        title: "Research"
                    description: "Claude"
                """);
        var config = loader.parse(root);
        assertEquals(1, config.getWorkerTemplate().size());

        var claude = config.getWorkerTemplate().get(0);
        assertEquals("llm-console-claude", claude.getName());
        assertFalse(claude.isSingleton());
        assertEquals("8200-8299", claude.getPortRange());
        assertEquals(2, claude.getInstances().size());
        assertEquals(8200, claude.getInstances().get(0).getPort());
        assertEquals("Coding", claude.getInstances().get(0).getTitle());
    }

    @Test
    void parseRemotes() {
        var root = parseYaml("""
                remotes:
                  local:
                    description: "This machine"
                  stonefly515:
                    address: 192.168.5.15
                    description: "DGX Spark"
                """);
        var config = loader.parse(root);
        assertEquals(2, config.getRemotes().size());

        var remote = config.getRemotes().stream()
                .filter(r -> "stonefly515".equals(r.getName())).findFirst().orElseThrow();
        assertEquals("192.168.5.15", remote.getAddress());
        assertEquals("DGX Spark", remote.getDescription());
    }

    @Test
    void parseEmptyYaml() {
        var config = loader.parse(Map.of());
        assertEquals("LXD-pups Portal", config.getTitle());
        assertEquals(8080, config.getPort());
        assertTrue(config.getManagementServices().isEmpty());
        assertTrue(config.getWorkerTemplate().isEmpty());
    }
}
