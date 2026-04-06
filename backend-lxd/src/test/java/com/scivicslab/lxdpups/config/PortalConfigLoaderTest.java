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
                    port: 15888
                    description: "MCP Gateway"
                    ui: "http://localhost:15888/"
                  predict-ja:
                    enabled: false
                    unit: predict-ja.service
                    port: 15190
                    description: "predict-ja"
                """);
        var config = loader.parse(root);
        assertEquals(2, config.getManagementServices().size());

        var gateway = config.getManagementServices().stream()
                .filter(s -> "mcp-gateway".equals(s.getName())).findFirst().orElseThrow();
        assertEquals("mcp-gateway.service", gateway.getUnit());
        assertEquals(15888, gateway.getPort());
        assertTrue(gateway.isEnabled());
        assertEquals("http://localhost:15888/", gateway.getUi());

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
                    port-range: 15200-15299
                    instances:
                      - port: 15200
                        title: "Coding"
                      - port: 15201
                        title: "Research"
                    description: "Claude"
                """);
        var config = loader.parse(root);
        assertEquals(1, config.getWorkerTemplate().size());

        var claude = config.getWorkerTemplate().get(0);
        assertEquals("llm-console-claude", claude.getName());
        assertFalse(claude.isSingleton());
        assertEquals("15200-15299", claude.getPortRange());
        assertEquals(2, claude.getInstances().size());
        assertEquals(15200, claude.getInstances().get(0).getPort());
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
        assertEquals(15080, config.getPort());
        assertTrue(config.getManagementServices().isEmpty());
        assertTrue(config.getWorkerTemplate().isEmpty());
    }

    @Test
    void parseManagementServiceWithBinary() {
        var root = parseYaml("""
                management:
                  mcp-gateway:
                    enabled: true
                    port: 15888
                    description: "MCP Gateway"
                    ui: "http://localhost:15888/"
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
                    port: 15190
                    description: "predict-ja"
                    binary:
                      repo: oogasawa/fcitx5-predict-ja
                      version: 0.1.0-SNAPSHOT
                      asset: fcitx5-predict-ja-0.1.0-SNAPSHOT.jar
                      path: ~/bin/fcitx5-predict-ja.jar
                      runtime: java
                      args: "--gateway-url http://localhost:15888 --vllm-url http://192.168.5.15:8000"
                """);
        var config = loader.parse(root);
        var svc = config.getManagementServices().get(0);
        assertNotNull(svc.getBinary());
        assertEquals("java", svc.getBinary().getRuntime());
        assertEquals("--gateway-url http://localhost:15888 --vllm-url http://192.168.5.15:8000", svc.getBinary().getArgs());
    }

    @Test
    void parseManagementServiceWithoutBinary() {
        var root = parseYaml("""
                management:
                  predict-en:
                    enabled: false
                    port: 15191
                    description: "predict-en"
                """);
        var config = loader.parse(root);
        var svc = config.getManagementServices().get(0);
        assertNull(svc.getBinary());
    }

    // ── host-tools tests ──

    @Test
    void parseHostToolsWithUrl() {
        var root = parseYaml("""
                host-tools:
                  llm-console-portal:
                    title: "LLM Console Portal"
                    description: "Launch and manage LLM console instances"
                    icon: "R"
                    url: "http://localhost:8093/"
                """);
        var config = loader.parse(root);
        assertEquals(1, config.getHostTools().size());

        var tool = config.getHostTools().get(0);
        assertEquals("llm-console-portal", tool.getName());
        assertEquals("LLM Console Portal", tool.getTitle());
        assertEquals("Launch and manage LLM console instances", tool.getDescription());
        assertEquals("R", tool.getIcon());
        assertEquals("http://localhost:8093/", tool.getUrl());
        assertNull(tool.getLxcImage());
        assertTrue(tool.isLink());
        assertFalse(tool.isLxcLaunch());
    }

    @Test
    void parseHostToolsWithLxcImage() {
        var root = parseYaml("""
                host-tools:
                  jupyter:
                    title: "Jupyter"
                    description: "Jupyter Lab"
                    icon: "J"
                    lxc-image: "lxd-pups/jupyter"
                """);
        var config = loader.parse(root);
        assertEquals(1, config.getHostTools().size());

        var tool = config.getHostTools().get(0);
        assertEquals("jupyter", tool.getName());
        assertEquals("Jupyter", tool.getTitle());
        assertEquals("Jupyter Lab", tool.getDescription());
        assertEquals("J", tool.getIcon());
        assertNull(tool.getUrl());
        assertEquals("lxd-pups/jupyter", tool.getLxcImage());
        assertFalse(tool.isLink());
        assertTrue(tool.isLxcLaunch());
    }

    @Test
    void parseHostToolsMixed() {
        var root = parseYaml("""
                host-tools:
                  llm-console-portal:
                    title: "LLM Console"
                    description: "LLM Console"
                    icon: "R"
                    url: "http://localhost:8093/"
                  jupyter:
                    title: "Jupyter"
                    description: "Jupyter Lab"
                    icon: "J"
                    lxc-image: "lxd-pups/jupyter"
                  remote-desktop:
                    title: "Remote Desktop"
                    description: "MATE desktop"
                    icon: "D"
                    lxc-image: "lxd-pups/guacamole"
                  workflow-portal:
                    title: "Workflow Portal"
                    description: "Workflow Portal"
                    icon: "W"
                    url: "http://localhost:15300/"
                """);
        var config = loader.parse(root);
        assertEquals(4, config.getHostTools().size());

        var links = config.getHostTools().stream().filter(PortalConfig.HostTool::isLink).toList();
        var launches = config.getHostTools().stream().filter(PortalConfig.HostTool::isLxcLaunch).toList();
        assertEquals(2, links.size());
        assertEquals(2, launches.size());
    }

    @Test
    void parseEmptyHostToolsSection() {
        var root = parseYaml("""
                portal:
                  title: "Test"
                """);
        var config = loader.parse(root);
        assertTrue(config.getHostTools().isEmpty());
    }

    @Test
    void hostToolTitleFallsBackToName() {
        var root = parseYaml("""
                host-tools:
                  my-tool:
                    description: "No title set"
                    icon: "X"
                    url: "http://localhost:1234/"
                """);
        var config = loader.parse(root);
        var tool = config.getHostTools().get(0);
        assertEquals("my-tool", tool.getName());
        assertEquals("my-tool", tool.getTitle());
    }

    @Test
    void hostToolIsLinkReturnsFalseForNull() {
        var tool = new PortalConfig.HostTool();
        assertFalse(tool.isLink());
        assertFalse(tool.isLxcLaunch());
    }

    @Test
    void hostToolIsLinkReturnsFalseForEmpty() {
        var tool = new PortalConfig.HostTool();
        tool.setUrl("");
        tool.setLxcImage("");
        assertFalse(tool.isLink());
        assertFalse(tool.isLxcLaunch());
    }

    @Test
    void parseHostToolsPreservesAllFields() {
        var root = parseYaml("""
                host-tools:
                  yadoc-portal:
                    title: "yadoc Portal"
                    description: "Documentation portal"
                    icon: "P"
                    url: "http://localhost:3100/"
                """);
        var config = loader.parse(root);
        var tool = config.getHostTools().get(0);
        assertEquals("yadoc-portal", tool.getName());
        assertEquals("yadoc Portal", tool.getTitle());
        assertEquals("Documentation portal", tool.getDescription());
        assertEquals("P", tool.getIcon());
        assertEquals("http://localhost:3100/", tool.getUrl());
    }

    @Test
    void parseFullPortalYamlWithHostTools() {
        var root = parseYaml("""
                portal:
                  title: "Full Portal"
                  port: 16080
                management:
                  kafka:
                    enabled: true
                    port: 9092
                    description: "Kafka"
                host-tools:
                  console:
                    title: "Console"
                    description: "Console"
                    icon: "C"
                    url: "http://localhost:8093/"
                  jupyter:
                    title: "Jupyter"
                    description: "Jupyter"
                    icon: "J"
                    lxc-image: "lxd-pups/jupyter"
                remotes:
                  local:
                    description: "This machine"
                """);
        var config = loader.parse(root);
        assertEquals("Full Portal", config.getTitle());
        assertEquals(16080, config.getPort());
        assertEquals(1, config.getManagementServices().size());
        assertEquals(2, config.getHostTools().size());
        assertEquals(1, config.getRemotes().size());
    }

    @Test
    void parseBinaryWithBuildDir() {
        var root = parseYaml("""
                management:
                  mcp-gateway:
                    enabled: true
                    port: 15888
                    description: "MCP Gateway"
                    binary:
                      repo: scivicslab/quarkus-mcp-gateway
                      path: ~/bin/quarkus-mcp-gateway
                      build-dir: quarkus-mcp-gateway
                """);
        var config = loader.parse(root);
        var svc = config.getManagementServices().get(0);
        assertNotNull(svc.getBinary());
        assertEquals("quarkus-mcp-gateway", svc.getBinary().getBuildDir());
    }

    @Test
    void parseBinaryWithWorkDir() {
        var root = parseYaml("""
                management:
                  docusearch:
                    enabled: true
                    port: 15100
                    description: "DocuSearch"
                    binary:
                      repo: scivicslab/docusearch
                      path: ~/bin/docusearch
                      work-dir: ~/works/doc_SCIVICS003
                """);
        var config = loader.parse(root);
        var svc = config.getManagementServices().get(0);
        assertNotNull(svc.getBinary());
        assertEquals("~/works/doc_SCIVICS003", svc.getBinary().getWorkDir());
    }

    @Test
    void parseBinaryWithUrlAndInstallDir() {
        var root = parseYaml("""
                management:
                  kafka:
                    enabled: true
                    port: 9092
                    description: "Kafka"
                    binary:
                      path: ~/kafka/bin/kafka-server-start.sh
                      url: "https://downloads.apache.org/kafka/4.0.2/kafka_2.13-4.0.2.tgz"
                      install-dir: ~/kafka
                      post-install-cmd: "bash ~/.lxd-pups/kafka-data/kafka-setup.sh"
                      args: "~/.lxd-pups/kafka-data/server.properties"
                """);
        var config = loader.parse(root);
        var bin = config.getManagementServices().get(0).getBinary();
        assertNotNull(bin);
        assertEquals("~/kafka/bin/kafka-server-start.sh", bin.getPath());
        assertEquals("https://downloads.apache.org/kafka/4.0.2/kafka_2.13-4.0.2.tgz", bin.getUrl());
        assertEquals("~/kafka", bin.getInstallDir());
        assertEquals("bash ~/.lxd-pups/kafka-data/kafka-setup.sh", bin.getPostInstallCmd());
        assertEquals("~/.lxd-pups/kafka-data/server.properties", bin.getArgs());
    }

    // ── timeout config tests ──

    @Test
    void parseTimeoutDefaults() {
        var config = loader.parse(Map.of());
        assertEquals(1440, config.getIdleTimeoutMinutes());
        assertEquals(10080, config.getMaxLifetimeMinutes());
        assertEquals(30, config.getFailedRetentionMinutes());
    }

    @Test
    void parseCustomTimeouts() {
        var root = parseYaml("""
                portal:
                  title: "Test"
                  timeouts:
                    idle-timeout-minutes: 60
                    max-lifetime-minutes: 1440
                    failed-retention-minutes: 10
                """);
        var config = loader.parse(root);
        assertEquals(60, config.getIdleTimeoutMinutes());
        assertEquals(1440, config.getMaxLifetimeMinutes());
        assertEquals(10, config.getFailedRetentionMinutes());
    }

    @Test
    void parsePartialTimeouts() {
        var root = parseYaml("""
                portal:
                  title: "Test"
                  timeouts:
                    idle-timeout-minutes: 120
                """);
        var config = loader.parse(root);
        assertEquals(120, config.getIdleTimeoutMinutes());
        assertEquals(10080, config.getMaxLifetimeMinutes());
        assertEquals(30, config.getFailedRetentionMinutes());
    }

}
