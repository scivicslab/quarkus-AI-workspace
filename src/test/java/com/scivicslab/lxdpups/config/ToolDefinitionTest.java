package com.scivicslab.lxdpups.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ToolDefinition port range parsing.
 */
class ToolDefinitionTest {

    @Test
    void getPortStartAndEnd() {
        var tool = new PortalConfig.ToolDefinition();
        tool.setPortRange("15200-15209");
        assertEquals(15200, tool.getPortStart());
        assertEquals(15209, tool.getPortEnd());
    }

    @Test
    void getPortStartAndEndSinglePort() {
        var tool = new PortalConfig.ToolDefinition();
        tool.setPortRange("15000-15000");
        assertEquals(15000, tool.getPortStart());
        assertEquals(15000, tool.getPortEnd());
    }

    @Test
    void getPortStartAndEndNullRange() {
        var tool = new PortalConfig.ToolDefinition();
        tool.setPortRange(null);
        assertEquals(0, tool.getPortStart());
        assertEquals(0, tool.getPortEnd());
    }

    @Test
    void getPortStartAndEndLargeRange() {
        var tool = new PortalConfig.ToolDefinition();
        tool.setPortRange("15000-15009");
        assertEquals(15000, tool.getPortStart());
        assertEquals(15009, tool.getPortEnd());
    }

    @Test
    void toolDefinitionProperties() {
        var tool = new PortalConfig.ToolDefinition();
        tool.setName("test-tool");
        tool.setDescription("Test Tool");
        tool.setIcon("T");
        tool.setPortRange("19000-19009");

        assertEquals("test-tool", tool.getName());
        assertEquals("Test Tool", tool.getDescription());
        assertEquals("T", tool.getIcon());
        assertEquals("19000-19009", tool.getPortRange());
    }

    @Test
    void toolDefinitionWithBinary() {
        var tool = new PortalConfig.ToolDefinition();
        var binary = new PortalConfig.ManagementService.Binary();
        binary.setRepo("org/repo");
        binary.setVersion("v1.0");
        binary.setPath("~/bin/test");
        tool.setBinary(binary);

        assertNotNull(tool.getBinary());
        assertEquals("org/repo", tool.getBinary().getRepo());
    }
}
