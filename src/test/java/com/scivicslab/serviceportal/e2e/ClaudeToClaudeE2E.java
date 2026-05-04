package com.scivicslab.serviceportal.e2e;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class ClaudeToClaudeE2E {

    private static final int POLL_TIMEOUT_MS = 60_000;
    private static final String HOME = System.getProperty("user.home");

    public static void main(String[] args) {
        try { new ClaudeToClaudeE2E().run(); }
        catch (Exception e) { e.printStackTrace(); System.exit(1); }
    }

    void run() throws Exception {
        System.out.println("--- ClaudeToClaudeE2E ---");
        runAgentPair("claude", "claude", -1);
        System.out.println("ClaudeToClaudeE2E: PASSED");
    }

    private static void reregister(int gatewayPort, String name, int chatUiPort) throws Exception {
        String body = "{\"name\":\"" + name + "\","
                + "\"url\":\"http://localhost:" + chatUiPort + "\","
                + "\"description\":\"" + name + "\"}";
        E2EHttp.postRaw("http://localhost:" + gatewayPort + "/api/servers", body);
    }

    /** -1 for mockVllmPort means no mock vLLM needed. */
    static void runAgentPair(String providerA, String providerB, int mockVllmPort) throws Exception {
        Path configPath = E2EConfig.configYaml();
        Path jarsDir    = E2EConfig.testJarsDir();
        int portalPort  = E2EConfig.findFreePortBase(20);

        ServicePortalProcess portal = ServicePortalProcess.start(
                configPath, portalPort, Map.of("TEST_JARS_DIR", jarsDir.toString()));
        try {
            Map<String, String> paramsA = buildParams(providerA, "agent-a", mockVllmPort);
            Map<String, String> paramsB = buildParams(providerB, "agent-b", mockVllmPort);

            E2EHttp.post(portalPort, "/api/tool/quarkus-chat-ui/launch", paramsA);
            E2EHttp.post(portalPort, "/api/tool/quarkus-chat-ui/launch", paramsB);

            List<Integer> ports = E2EHttp.waitForAllToolsReady(portalPort, "quarkus-chat-ui", 2, POLL_TIMEOUT_MS);
            int portA = ports.get(0);
            int portB = ports.get(1);

            // Wait for gateway fully initialized, then re-register (portal registers before CDI completes)
            E2EHttp.waitForManagementServiceReady(portalPort, "quarkus-mcp-gateway", 90_000);
            reregister(portalPort + 1, "quarkus-chat-ui-" + portA, portA);
            reregister(portalPort + 1, "quarkus-chat-ui-" + portB, portB);
            Thread.sleep(3_000);

            String gatewayMcp = "http://localhost:" + (portalPort + 1) + "/mcp";
            System.out.println("  gateway: " + gatewayMcp);

            // Call agent-b's getStatus via gateway — synchronous, no LLM needed
            String toolName = "quarkus-chat-ui-" + portB + "__getStatus";
            String result = E2EHttp.postRaw(gatewayMcp,
                    "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"id\":2," +
                    "\"params\":{\"name\":\"" + toolName + "\",\"arguments\":{}}}");
            System.out.println("  getStatus result: " + result.substring(0, Math.min(300, result.length())));
            E2EHttp.assertContains(result, "result", "gateway call to agent-b must return a result");

            for (int p : ports) {
                E2EHttp.post(portalPort, "/api/tool/quarkus-chat-ui/" + p + "/stop", Map.of());
            }
        } finally {
            portal.stop();
        }
    }

    private static Map<String, String> buildParams(String provider, String name, int mockVllmPort) {
        Map<String, String> params = new HashMap<>();
        params.put("provider", provider);
        params.put("workdir", HOME + "/works");
        params.put("name", name);
        if ("openai-compat".equals(provider) && mockVllmPort > 0) {
            params.put("servers", "http://localhost:" + mockVllmPort);
        }
        return params;
    }
}
