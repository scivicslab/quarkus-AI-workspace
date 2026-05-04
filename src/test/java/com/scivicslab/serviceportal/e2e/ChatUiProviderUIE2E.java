package com.scivicslab.serviceportal.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitUntilState;

import java.nio.file.Path;
import java.util.Map;

/**
 * E2E: verifies that the quarkus-chat-ui web UI reflects the correct provider
 * when launched via service-portal (browser-level check via Playwright).
 *
 * Run via ServicePortalE2ERunner.
 */
class ChatUiProviderUIE2E {

    private static final int POLL_TIMEOUT_MS = 60_000;
    private static final String HOME = System.getProperty("user.home");

    private int chatUiPort;
    private int portalPort;

    void run() throws Exception {
        System.out.println("--- ChatUiProviderUIE2E ---");
        Path configPath  = E2EConfig.configYaml();
        Path testJarsDir = E2EConfig.testJarsDir();

        int mockVllmPort = E2EConfig.findFreePort();
        MockVllmServer mockVllm = new MockVllmServer(mockVllmPort);
        mockVllm.start();
        portalPort = E2EConfig.findFreePortBase(20);
        ServicePortalProcess portal = ServicePortalProcess.start(
                configPath, portalPort, Map.of("TEST_JARS_DIR", testJarsDir.toString()));

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true));
            try {
                testUi(browser, "claude",
                        Map.of("provider", "claude", "workdir", HOME + "/works"));
                testUi(browser, "codex",
                        Map.of("provider", "codex", "workdir", HOME + "/works"));
                testUi(browser, "openai-compat",
                        Map.of("provider", "openai-compat",
                               "servers",  "http://localhost:" + mockVllmPort,
                               "workdir",  HOME + "/works"));
            } finally {
                browser.close();
                portal.stop();
                mockVllm.stop();
            }
        }
        System.out.println("ChatUiProviderUIE2E: PASSED");
    }

    private void testUi(Browser browser, String provider, Map<String, String> params) throws Exception {
        System.out.println("  UI provider=" + provider + ": launching...");
        E2EHttp.post(portalPort, "/api/tool/quarkus-chat-ui/launch", params);
        chatUiPort = E2EHttp.waitForToolReady(portalPort, "quarkus-chat-ui", POLL_TIMEOUT_MS);
        try (Page page = browser.newPage()) {
            page.navigate("http://localhost:" + chatUiPort,
                    new Page.NavigateOptions().setWaitUntil(WaitUntilState.LOAD));
            page.locator("#app").waitFor(
                    new com.microsoft.playwright.Locator.WaitForOptions().setTimeout(10_000));

            if (!page.locator("#prompt-input").isVisible())
                throw new AssertionError("provider=" + provider + ": #prompt-input must be visible in single-user mode");
            if (page.locator("#login-screen").isVisible())
                throw new AssertionError("provider=" + provider + ": #login-screen must be hidden in single-user mode");
        } finally {
            stopChatUi();
        }
        System.out.println("  UI provider=" + provider + ": PASSED");
    }

    private void stopChatUi() throws Exception {
        E2EHttp.post(portalPort, "/api/tool/quarkus-chat-ui/" + chatUiPort + "/stop", Map.of());
        E2EHttp.waitForToolStopped(portalPort, "quarkus-chat-ui", 10_000);
    }
}
