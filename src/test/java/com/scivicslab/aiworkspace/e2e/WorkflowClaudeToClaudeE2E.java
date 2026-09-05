package com.scivicslab.aiworkspace.e2e;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class WorkflowClaudeToClaudeE2E {

    private static final int POLL_TIMEOUT_MS = 60_000;
    private static final String HOME = System.getProperty("user.home");

    /**
     * The workflow agent A runs: point the LLM actor at agent B's own REST API and submit a prompt.
     *
     * <p>{@code setDirectUrl} + {@code submitDirect} POST to {@code /api/chat/submit} on a
     * quarkus-chat-ui and poll for the result — no MCP session, no aggregating middleman. The URL
     * comes from the portal's service directory, which is how one tool learns where another is
     * ({@code ServiceDirectory_260905_oo01}).</p>
     */
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
            "        method: setDirectUrl\n" +
            "        arguments: \"{agentBUrl}\"\n" +
            "  - states: [\"3\", \"end\"]\n" +
            "    actions:\n" +
            "      - actor: caller\n" +
            "        method: submitDirect\n" +
            "        arguments: \"Hello from workflow\"\n";

    public static void main(String[] args) {
        try { new WorkflowClaudeToClaudeE2E().run(); }
        catch (Exception e) { e.printStackTrace(); System.exit(1); }
    }

    void run() throws Exception {
        System.out.println("--- WorkflowClaudeToClaudeE2E ---");
        runWorkflowScenario("claude", "claude", -1);
        System.out.println("WorkflowClaudeToClaudeE2E: PASSED");
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

            // Where agent B is, asked of the portal rather than of a middleman.
            String directory = E2EHttp.get(portalPort, "/api/services?name=quarkus-chat-ui&readyOnly=true");
            String agentBUrl = urlOf(directory, portB);
            System.out.println("  agent-b at " + agentBUrl + " (from the portal's service directory)");

            String yaml = YAML_TEMPLATE.replace("{agentBUrl}", agentBUrl);

            // POST YAML as text/plain to /api/run/yaml — imports and starts in one call
            String runResult = E2EHttp.postText(
                    "http://localhost:" + editorPort + "/api/run/yaml", yaml);
            E2EHttp.assertContains(runResult, "started", "workflow must start successfully");
            System.out.println("  workflow started: " + runResult);

            waitForWorkflowDone(editorPort, 300_000);
            System.out.println("  workflow done");

            // Agent B answered if its own conversation now holds the workflow's prompt.
            String history = E2EHttp.getUrl(agentBUrl + "api/history");
            E2EHttp.assertContains(history, "Hello from workflow",
                    "agent-b must hold the prompt the workflow submitted to it");
            System.out.println("  agent-b holds the workflow's prompt");

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

    /**
     * The {@code url} of the directory entry whose {@code port} is {@code port}.
     *
     * <p>Read by hand rather than with a JSON library: this test module has no JSON dependency, and
     * the shape being read is three fields of one object.</p>
     */
    private static String urlOf(String directoryJson, int port) {
        int at = directoryJson.indexOf("\"port\":" + port);
        if (at < 0) throw new AssertionError("no directory entry for port " + port + ": " + directoryJson);
        int urlAt = directoryJson.indexOf("\"url\":\"", at);
        if (urlAt < 0) throw new AssertionError("directory entry has no url: " + directoryJson);
        urlAt += "\"url\":\"".length();
        return directoryJson.substring(urlAt, directoryJson.indexOf('"', urlAt));
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
