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

    @Test
    void parseManagementServiceWithBinary() {
        var root = parseYaml("""
                management:
                  mcp-gateway:
                    enabled: true
                    port: 8888
                    description: "MCP Gateway"
                    ui: "http://localhost:8888/"
                    binary:
                      repo: scivicslab/quarkus-mcp-gateway
                      version: v1.0.0
                      asset: quarkus-mcp-gateway-v1.0.0-linux-x86_64
                      path: ~/bin/quarkus-mcp-gateway
                """);
        var config = loader.parse(root);
        assertEquals(1, config.getManagementServices().size());

        var svc = config.getManagementServices().get(0);
        assertEquals("mcp-gateway", svc.getName());
        assertNotNull(svc.getBinary());
        assertEquals("scivicslab/quarkus-mcp-gateway", svc.getBinary().getRepo());
        assertEquals("v1.0.0", svc.getBinary().getVersion());
        assertEquals("quarkus-mcp-gateway-v1.0.0-linux-x86_64", svc.getBinary().getAsset());
        assertEquals("~/bin/quarkus-mcp-gateway", svc.getBinary().getPath());
        assertNull(svc.getBinary().getRuntime());
        assertNull(svc.getBinary().getArgs());
    }

    @Test
    void parseManagementServiceWithJarBinary() {
        var root = parseYaml("""
                management:
                  predict-ja:
                    enabled: true
                    port: 8190
                    description: "predict-ja"
                    binary:
                      repo: oogasawa/fcitx5-predict-ja
                      version: 0.1.0-SNAPSHOT
                      asset: fcitx5-predict-ja-0.1.0-SNAPSHOT.jar
                      path: ~/bin/fcitx5-predict-ja.jar
                      runtime: java
                      args: "--gateway-url http://localhost:8888 --vllm-url http://192.168.5.15:8000"
                """);
        var config = loader.parse(root);
        var svc = config.getManagementServices().get(0);
        assertNotNull(svc.getBinary());
        assertEquals("java", svc.getBinary().getRuntime());
        assertEquals("--gateway-url http://localhost:8888 --vllm-url http://192.168.5.15:8000", svc.getBinary().getArgs());
    }

    @Test
    void parseManagementServiceWithoutBinary() {
        var root = parseYaml("""
                management:
                  predict-en:
                    enabled: false
                    port: 8191
                    description: "predict-en"
                """);
        var config = loader.parse(root);
        var svc = config.getManagementServices().get(0);
        assertNull(svc.getBinary());
    }
}
