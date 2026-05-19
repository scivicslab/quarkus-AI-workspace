package com.scivicslab.aiworkspace.e2e;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class WorkflowClaudeToClaudeE2E {

    private static final int POLL_TIMEOUT_MS = 60_000;
    private static final String HOME = System.getProperty("user.home");

    static final String YAML_TEMPLATE =
            "name: call-agent-b\n" +
            "steps:\n" +
            "  - states: [\"0\", \"1\"]\n" +
            "    actions:\n" +
            "      - actor: loader\n" +
            "        method: loadJar\n" +
            "        arguments: \"com.scivicslab.turingworkflow.plugins:plugin-llm:1.0.0\"\n" +
            "  - states: [\"1\", \"2\"]\n" +
            "    actions:\n" +
            "      - actor: loader\n" +
            "        method: createChild\n" +
            "        arguments: [\"ROOT\", \"caller\", \"com.scivicslab.turingworkflow.plugins.llm.LlmActor\"]\n" +
            "  - states: [\"2\", \"3\"]\n" +
            "    actions:\n" +
            "      - actor: caller\n" +
            "        method: setUrl\n" +
            "        arguments: \"http://localhost:{gatewayPort}/mcp\"\n" +
            "  - states: [\"3\", \"end\"]\n" +
            "    actions:\n" +
            "      - actor: caller\n" +
            "        method: callAgent\n" +
            "        arguments: \"{agentBName},Hello from workflow\"\n";

    public static void main(String[] args) {
        try { new WorkflowClaudeToClaudeE2E().run(); }
        catch (Exception e) { e.printStackTrace(); System.exit(1); }
    }

    void run() throws Exception {
        System.out.println("--- WorkflowClaudeToClaudeE2E ---");
        runWorkflowScenario("claude", "claude", -1);
        System.out.println("WorkflowClaudeToClaudeE2E: PASSED");
    }

    private static void reregister(int gatewayPort, String name, int chatUiPort) throws Exception {
        String body = "{\"name\":\"" + name + "\","
                + "\"url\":\"http://localhost:" + chatUiPort + "\","
                + "\"description\":\"" + name + "\"}";
        E2EHttp.postRaw("http://localhost:" + gatewayPort + "/api/servers", body);
    }

    static void runWorkflowScenario(String providerA, String providerB, int mockVllmPort) throws Exception {
        Path configPath = E2EConfig.configYaml();
        Path jarsDir    = E2EConfig.testJarsDir();
        int portalPort  = E2EConfig.findFreePortBase(20);

        AiWorkspaceProcess portal = AiWorkspaceProcess.start(
                configPath, portalPort, Map.of("TEST_JARS_DIR", jarsDir.toString()));
        try {
            E2EHttp.post(portalPort, "/api/tool/quarkus-chat-ui/launch",
                    buildParams(providerA, "agent-a", mockVllmPort));
            E2EHttp.post(portalPort, "/api/tool/quarkus-chat-ui/launch",
                    buildParams(providerB, "agent-b", mockVllmPort));
            E2EHttp.post(portalPort, "/api/tool/turing-workflow-editor/launch", Map.of());

            List<Integer> chatPorts = E2EHttp.waitForAllToolsReady(
                    portalPort, "quarkus-chat-ui", 2, POLL_TIMEOUT_MS);
            int portA      = chatPorts.get(0);
            int portB      = chatPorts.get(1);
            int editorPort = E2EHttp.waitForToolReady(portalPort, "turing-workflow-editor", POLL_TIMEOUT_MS);

            // Wait for gateway CDI to complete before re-registering chat-ui instances.
            // Portal registers on READY but gateway CDI reset wipes it; must re-register after READY.
            E2EHttp.waitForManagementServiceReady(portalPort, "quarkus-mcp-gateway", 90_000);
            reregister(portalPort + 1, "quarkus-chat-ui-" + portA, portA);
            reregister(portalPort + 1, "quarkus-chat-ui-" + portB, portB);
            Thread.sleep(3_000);

            String gatewayPort = String.valueOf(portalPort + 1);
            System.out.println("  gateway port: " + gatewayPort + ", agent-b server: quarkus-chat-ui-" + portB);

            // agentBName must match the gateway-registered server name (quarkus-chat-ui-{port})
            String yaml = YAML_TEMPLATE
                    .replace("{gatewayPort}", gatewayPort)
                    .replace("{agentBName}", "quarkus-chat-ui-" + portB);

            // POST YAML as text/plain to /api/run/yaml — imports and starts in one call
            String runResult = E2EHttp.postText(
                    "http://localhost:" + editorPort + "/api/run/yaml", yaml);
            E2EHttp.assertContains(runResult, "started", "workflow must start successfully");
            System.out.println("  workflow started: " + runResult);

            waitForWorkflowDone(editorPort, 300_000);
            System.out.println("  workflow done");

            // Verify agent-b was actually called by checking its last reply via gateway
            String gatewayMcp = "http://localhost:" + (portalPort + 1) + "/mcp";
            String lastReply = E2EHttp.postRaw(gatewayMcp,
                    "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"id\":3," +
                    "\"params\":{\"name\":\"quarkus-chat-ui-" + portB + "__getLastReply\",\"arguments\":{}}}");
            E2EHttp.assertContains(lastReply, "result", "agent-b must have a reply after workflow callAgent");
            System.out.println("  agent-b replied via workflow");

            for (int p : chatPorts) {
                E2EHttp.post(portalPort, "/api/tool/quarkus-chat-ui/" + p + "/stop", Map.of());
            }
            E2EHttp.post(portalPort, "/api/tool/turing-workflow-editor/" + editorPort + "/stop", Map.of());
        } finally {
            portal.stop();
        }
    }

    static void waitForWorkflowDone(int editorPort, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String status = E2EHttp.get(editorPort, "/api/status");
            if (status.contains("\"running\":false")) return;
            Thread.sleep(2_000);
        }
        throw new AssertionError("Workflow did not complete within " + timeoutMs + "ms");
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
