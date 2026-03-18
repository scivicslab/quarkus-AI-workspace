package com.scivicslab.lxdpups.service;

import com.scivicslab.lxdpups.config.PortalConfig;
import com.scivicslab.lxdpups.model.ServiceStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ToolInstanceManager port allocation logic.
 * Uses high port numbers (59000+) that are unlikely to be occupied.
 */
class ToolInstanceManagerTest {

    @Test
    void findAvailablePortReturnsFirstPort() {
        var pm = new ProcessManager();
        var tim = new ToolInstanceManager();

        var tool = createTool("test-tool", "59200-59209");
        int port = tim.findAvailablePortWith(tool, pm);
        assertEquals(59200, port);
    }

    @Test
    void findAvailablePortSinglePortRange() {
        var pm = new ProcessManager();
        var tim = new ToolInstanceManager();

        var tool = createTool("test-tool", "59300-59300");
        int port = tim.findAvailablePortWith(tool, pm);
        assertEquals(59300, port);
    }

    @Test
    void findAvailablePortNullRange() {
        var pm = new ProcessManager();
        var tim = new ToolInstanceManager();

        var tool = createTool("test-tool", null);
        int port = tim.findAvailablePortWith(tool, pm);
        assertEquals(-1, port);
    }

    @Test
    void findAvailablePortZeroRange() {
        var pm = new ProcessManager();
        var tim = new ToolInstanceManager();

        // portStart=0 and portEnd=0 when portRange is null
        var tool = new PortalConfig.ToolDefinition();
        tool.setName("test");
        tool.setPortRange(null);
        assertEquals(-1, tim.findAvailablePortWith(tool, pm));
    }

    private PortalConfig.ToolDefinition createTool(String name, String portRange) {
        var tool = new PortalConfig.ToolDefinition();
        tool.setName(name);
        tool.setPortRange(portRange);
        tool.setDescription("Test");
        return tool;
    }
}
