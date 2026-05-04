package com.scivicslab.serviceportal.e2e;

import java.nio.file.Path;
import java.util.Map;

/**
 * E2E: verifies that quarkus-chat-ui starts with the correct provider
 * and single-user mode when launched via service-portal.
 *
 * Run via ServicePortalE2ERunner.
 */
class ChatUiProviderE2E {

    private static final int POLL_TIMEOUT_MS = 60_000;
    private static final String HOME = System.getProperty("user.home");

    private int chatUiPort;
    private int portalPort;

    void run() throws Exception {
        System.out.println("--- ChatUiProviderE2E ---");
        Path configPath  = E2EConfig.configYaml();
        Path testJarsDir = E2EConfig.testJarsDir();

        int mockVllmPort = E2EConfig.findFreePort();
        MockVllmServer mockVllm = new MockVllmServer(mockVllmPort);
        mockVllm.start();
        portalPort = E2EConfig.findFreePortBase(20);
        ServicePortalProcess portal = ServicePortalProcess.start(
                configPath, portalPort, Map.of("TEST_JARS_DIR", testJarsDir.toString()));
        try {
            testProvider("claude",       Map.of("provider", "claude", "workdir", HOME + "/works"));
            testProvider("codex",        Map.of("provider", "codex",  "workdir", HOME + "/works"));
            testProvider("openai-compat", Map.of(
                    "provider", "openai-compat",
                    "servers",  "http://localhost:" + mockVllmPort,
                    "workdir",  HOME + "/works"));
        } finally {
            portal.stop();
            mockVllm.stop();
        }
        System.out.println("ChatUiProviderE2E: PASSED");
    }

    private void testProvider(String provider, Map<String, String> params) throws Exception {
        System.out.println("  provider=" + provider + ": launching...");
        E2EHttp.post(portalPort, "/api/tool/quarkus-chat-ui/launch", params);
        chatUiPort = E2EHttp.waitForToolReady(portalPort, "quarkus-chat-ui", POLL_TIMEOUT_MS);
        try {
            String config = E2EHttp.get(chatUiPort, "/api/config");
            String expectedProvider = provider.equals("openai-compat") ? "openai-compat" : provider;
            E2EHttp.assertContains(config, "\"providerId\":\"" + expectedProvider + "\"",
                    "provider=" + provider + " config check");
            E2EHttp.assertContains(config, "\"multiUser\":false",
                    "provider=" + provider + " must be single-user");
        } finally {
            stopChatUi();
        }
        System.out.println("  provider=" + provider + ": PASSED");
    }

    private void stopChatUi() throws Exception {
        E2EHttp.post(portalPort, "/api/tool/quarkus-chat-ui/" + chatUiPort + "/stop", Map.of());
        E2EHttp.waitForToolStopped(portalPort, "quarkus-chat-ui", 10_000);
    }
}
