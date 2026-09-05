package com.scivicslab.aiworkspace.e2e;

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

    /** -1 for mockVllmPort means no mock vLLM needed. */
    static void runAgentPair(String providerA, String providerB, int mockVllmPort) throws Exception {
        Path configPath = E2EConfig.configYaml();
        Path jarsDir    = E2EConfig.testJarsDir();
        int portalPort  = E2EConfig.findFreePortBase(20);

        AiWorkspaceProcess portal = AiWorkspaceProcess.start(
                configPath, portalPort, Map.of("TEST_JARS_DIR", jarsDir.toString()));
        try {
            Map<String, String> paramsA = buildParams(providerA, "agent-a", mockVllmPort);
            Map<String, String> paramsB = buildParams(providerB, "agent-b", mockVllmPort);

            E2EHttp.post(portalPort, "/api/tool/quarkus-chat-ui/launch", paramsA);
            E2EHttp.post(portalPort, "/api/tool/quarkus-chat-ui/launch", paramsB);

            List<Integer> ports = E2EHttp.waitForAllToolsReady(portalPort, "quarkus-chat-ui", 2, POLL_TIMEOUT_MS);
            int portA = ports.get(0);
            int portB = ports.get(1);

            // Agent A reaches agent B the way ServiceDirectory_260905_oo01 describes: it asks the
            // portal which quarkus-chat-ui instances are running, and then calls the one it wants
            // directly. There is no aggregating middleman any more — the portal answers where, and
            // the tool's own HTTP API answers what.
            String directory = E2EHttp.get(portalPort, "/api/services?name=quarkus-chat-ui&readyOnly=true");
            System.out.println("  directory: " + directory);
            E2EHttp.assertContains(directory, "\"port\":" + portA,
                    "the directory must list agent-a on " + portA);
            E2EHttp.assertContains(directory, "\"port\":" + portB,
                    "the directory must list agent-b on " + portB);

            String urlB = urlOf(directory, portB);
            System.out.println("  agent-b at " + urlB);

            // Synchronous, no LLM needed — the same thing the gateway's getStatus tool used to reach.
            String result = E2EHttp.getUrl(urlB + "api/status");
            System.out.println("  agent-b status: " + result.substring(0, Math.min(300, result.length())));
            E2EHttp.assertContains(result, "model",
                    "agent-b, found through the directory, must answer its own status");

            for (int p : ports) {
                E2EHttp.post(portalPort, "/api/tool/quarkus-chat-ui/" + p + "/stop", Map.of());
            }
        } finally {
            portal.stop();
        }
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
