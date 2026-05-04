package com.scivicslab.serviceportal.e2e;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

class GatewayAggregationE2E {

    private static final int POLL_TIMEOUT_MS  = 60_000;
    private static final int GW_TIMEOUT_MS    = 90_000;
    private static final String HOME = System.getProperty("user.home");

    public static void main(String[] args) {
        try { new GatewayAggregationE2E().run(); }
        catch (Exception e) { e.printStackTrace(); System.exit(1); }
    }

    private static void reregister(int gatewayPort, String name, int chatUiPort) throws Exception {
        String body = "{\"name\":\"" + name + "\","
                + "\"url\":\"http://localhost:" + chatUiPort + "\","
                + "\"description\":\"" + name + "\"}";
        E2EHttp.postRaw("http://localhost:" + gatewayPort + "/api/servers", body);
        System.out.println("  registered: " + name + " -> http://localhost:" + chatUiPort);
    }

    void run() throws Exception {
        System.out.println("--- GatewayAggregationE2E ---");
        Path configPath = E2EConfig.configYaml();
        Path jarsDir    = E2EConfig.testJarsDir();
        int portalPort  = E2EConfig.findFreePortBase(20);

        ServicePortalProcess portal = ServicePortalProcess.start(
                configPath, portalPort, Map.of("TEST_JARS_DIR", jarsDir.toString()));
        try {
            E2EHttp.post(portalPort, "/api/tool/quarkus-chat-ui/launch",
                    Map.of("provider", "claude", "workdir", HOME + "/works", "name", "agent-a"));
            E2EHttp.post(portalPort, "/api/tool/quarkus-chat-ui/launch",
                    Map.of("provider", "claude", "workdir", HOME + "/works", "name", "agent-b"));

            List<Integer> ports = E2EHttp.waitForAllToolsReady(
                    portalPort, "quarkus-chat-ui", 2, POLL_TIMEOUT_MS);
            int portA = ports.get(0);
            int portB = ports.get(1);
            System.out.println("  chat-ui ready: " + portA + ", " + portB);

            // Wait for gateway fully initialized (takes ~33s due to servers.yaml timeouts)
            E2EHttp.waitForManagementServiceReady(portalPort, "quarkus-mcp-gateway", GW_TIMEOUT_MS);
            System.out.println("  gateway ready");

            // Re-register chat-ui instances: portal registered them before gateway CDI completed,
            // so the in-memory registry was reset. Register again now that gateway is fully up.
            int gatewayPort = portalPort + 1;
            reregister(gatewayPort, "quarkus-chat-ui-" + portA, portA);
            reregister(gatewayPort, "quarkus-chat-ui-" + portB, portB);
            System.out.println("  re-registered chat-ui instances");
            Thread.sleep(3_000); // wait for tool aggregation

            // Verify via MCP tools/list that gateway aggregates tools from both agents
            String gatewayMcp = "http://localhost:" + gatewayPort + "/mcp";
            String toolsList = E2EHttp.postRaw(gatewayMcp,
                    "{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":1}");
            System.out.println("  tools/list: " + toolsList.substring(0, Math.min(400, toolsList.length())));
            E2EHttp.assertContains(toolsList, "submitPrompt",
                    "gateway tools/list must include submitPrompt");
            E2EHttp.assertContains(toolsList, String.valueOf(portA),
                    "gateway must list tools from agent-a (port " + portA + ")");
            E2EHttp.assertContains(toolsList, String.valueOf(portB),
                    "gateway must list tools from agent-b (port " + portB + ")");

            E2EHttp.post(portalPort, "/api/tool/quarkus-chat-ui/" + portA + "/stop", Map.of());
            E2EHttp.post(portalPort, "/api/tool/quarkus-chat-ui/" + portB + "/stop", Map.of());
        } finally {
            portal.stop();
        }
        System.out.println("GatewayAggregationE2E: PASSED");
    }
}
