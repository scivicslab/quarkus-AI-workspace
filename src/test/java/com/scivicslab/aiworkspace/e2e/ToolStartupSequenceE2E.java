package com.scivicslab.aiworkspace.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.util.Map;

/**
 * E2E: tool startup sequence with fixed ports 28200-28213.
 *
 * Step 1: AI workspace starts on 28200
 * Step 2: chat-ui launched via portal lands on 28210, the first port of the dynamic
 *         pool — 28201-28209 are the reserved band (PortConvention_260719_oo01) and
 *         belong to the search services, which are not launched here
 * Step 3a: chat-ui started externally on 28211 is NOT adopted into portal sessions
 * Step 3b: chat-ui started externally on 28212 is NOT adopted into portal sessions
 * Step 4: turing-workflow-editor skips occupied 28211 and 28212, lands on 28213;
 *         accessUrl navigates to the workflow editor, not the chat UI
 *
 * The MCP gateway used to occupy 28201 and be waited for between steps 1 and 2. It no
 * longer exists, and the ports below moved off the numbers that assumed it.
 */
class ToolStartupSequenceE2E {

    private static final int PORTAL_PORT    = 28200;
    private static final int CHAT_UI_PORT   = 28210;
    private static final int EXTERNAL_PORT  = 28211;
    private static final int EXTERNAL_PORT2 = 28212;
    private static final int EDITOR_PORT    = 28213;

    private static final long TOOL_TIMEOUT_MS    = 60_000;
    private static final long STARTUP_TIMEOUT_MS = 30_000;
    private static final String HOME = System.getProperty("user.home");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) {
        try { new ToolStartupSequenceE2E().run(); }
        catch (Exception e) { e.printStackTrace(); System.exit(1); }
    }

    void run() throws Exception {
        System.out.println("--- ToolStartupSequenceE2E ---");
        Path jarsDir   = E2EConfig.testJarsDir();
        Path configPath = E2EConfig.configYaml();

        // Step 1
        System.out.println("  [step 1] starting AI workspace on " + PORTAL_PORT + "...");
        AiWorkspaceProcess portal = AiWorkspaceProcess.start(
                configPath, PORTAL_PORT, Map.of("TEST_JARS_DIR", jarsDir.toString()));

        Process externalChatUi  = null;
        Process externalChatUi2 = null;
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true));
            try {
                System.out.println("  [step 1] AI workspace READY on " + PORTAL_PORT);

                // Step 2: Launch chat-ui via portal; expect the first pool port = 28210
                System.out.println("  [step 2] launching chat-ui via portal...");
                E2EHttp.post(PORTAL_PORT, "/api/tool/quarkus-chat-ui/launch",
                        Map.of("provider", "claude", "workdir", HOME + "/works"));
                int chatUiPort = E2EHttp.waitForToolReady(PORTAL_PORT, "quarkus-chat-ui", TOOL_TIMEOUT_MS);
                if (chatUiPort != CHAT_UI_PORT)
                    throw new AssertionError("chat-ui must start on " + CHAT_UI_PORT
                            + " (the first port of the dynamic pool), but got " + chatUiPort);
                verifyVisible(browser, "http://localhost:" + chatUiPort + "/",
                        "#prompt-input", "portal-managed chat-ui on " + chatUiPort);
                System.out.println("  [step 2] chat-ui READY on " + chatUiPort + " (portal-managed) — PASSED");

                // Step 4: Start external chat-ui on 28203; verify portal does not adopt it
                System.out.println("  [step 3] starting external chat-ui on " + EXTERNAL_PORT + "...");
                Path chatUiJar = jarsDir.resolve("quarkus-chat-ui.jar");
                externalChatUi = new ProcessBuilder(
                        "java", "-Dquarkus.http.port=" + EXTERNAL_PORT,
                        "-jar", chatUiJar.toAbsolutePath().toString())
                        .directory(jarsDir.toFile())
                        .redirectErrorStream(true)
                        .start();
                waitForHttp("http://localhost:" + EXTERNAL_PORT + "/", STARTUP_TIMEOUT_MS);
                verifyVisible(browser, "http://localhost:" + EXTERNAL_PORT + "/",
                        "#prompt-input", "external chat-ui on " + EXTERNAL_PORT);
                verifyNotInSessions(EXTERNAL_PORT);
                System.out.println("  [step 3a] external chat-ui READY on " + EXTERNAL_PORT
                        + " (not in portal sessions) — PASSED");

                // Step 4b: Start another external chat-ui on 28204; verify portal does not adopt it
                System.out.println("  [step 3b] starting external chat-ui on " + EXTERNAL_PORT2 + "...");
                externalChatUi2 = new ProcessBuilder(
                        "java", "-Dquarkus.http.port=" + EXTERNAL_PORT2,
                        "-jar", chatUiJar.toAbsolutePath().toString())
                        .directory(jarsDir.toFile())
                        .redirectErrorStream(true)
                        .start();
                waitForHttp("http://localhost:" + EXTERNAL_PORT2 + "/", STARTUP_TIMEOUT_MS);
                verifyVisible(browser, "http://localhost:" + EXTERNAL_PORT2 + "/",
                        "#prompt-input", "external chat-ui on " + EXTERNAL_PORT2);
                verifyNotInSessions(EXTERNAL_PORT2);
                System.out.println("  [step 3b] external chat-ui READY on " + EXTERNAL_PORT2
                        + " (not in portal sessions) — PASSED");

                // Step 5: Launch turing-workflow-editor; 28203 and 28204 are occupied → 28205
                System.out.println("  [step 4] launching turing-workflow-editor via portal...");
                E2EHttp.post(PORTAL_PORT, "/api/tool/turing-workflow-editor/launch",
                        Map.of("workdir", HOME + "/works"));
                int editorPort = E2EHttp.waitForToolReady(PORTAL_PORT, "turing-workflow-editor", TOOL_TIMEOUT_MS);
                if (editorPort != EDITOR_PORT)
                    throw new AssertionError("turing-workflow-editor must start on " + EDITOR_PORT
                            + " (skipping occupied " + EXTERNAL_PORT + " and " + EXTERNAL_PORT2 + "), but got " + editorPort);
                String accessUrl = E2EHttp.waitForToolAccessUrl(PORTAL_PORT, "turing-workflow-editor", 5_000);
                verifyWorkflowEditorPage(browser, accessUrl, editorPort);
                System.out.println("  [step 4] turing-workflow-editor READY on " + editorPort
                        + ", accessUrl=" + accessUrl + " — PASSED");

            } finally {
                browser.close();
            }
        } finally {
            if (externalChatUi2 != null) {
                externalChatUi2.destroyForcibly();
                externalChatUi2.waitFor();
            }
            if (externalChatUi != null) {
                externalChatUi.destroyForcibly();
                externalChatUi.waitFor();
            }
            portal.stop();
        }
        System.out.println("ToolStartupSequenceE2E: PASSED");
    }

    private static void verifyVisible(Browser browser, String url, String selector, String context) {
        try (Page page = browser.newPage()) {
            page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.LOAD));
            page.locator(selector).waitFor(
                    new Locator.WaitForOptions()
                            .setTimeout(15_000)
                            .setState(WaitForSelectorState.VISIBLE));
        }
    }

    private static void verifyWorkflowEditorPage(Browser browser, String accessUrl, int expectedPort) {
        try (Page page = browser.newPage()) {
            page.navigate(accessUrl, new Page.NavigateOptions().setWaitUntil(WaitUntilState.LOAD));
            // Wait up to 10s for the workflow editor to render its main container
            page.locator("#stepsContainer").waitFor(
                    new Locator.WaitForOptions().setTimeout(10_000));

            if (!page.locator("#stepsContainer").isVisible())
                throw new AssertionError(
                        "turing-workflow-editor accessUrl must open the workflow editor " +
                        "(#stepsContainer not visible — wrong page opened). " +
                        "url=" + page.url() + " accessUrl=" + accessUrl);
            if (!page.locator("#runBtn").isVisible())
                throw new AssertionError(
                        "turing-workflow-editor: #runBtn not visible. url=" + page.url());
            if (page.locator("#prompt-input").isVisible())
                throw new AssertionError(
                        "turing-workflow-editor accessUrl opened chat UI (#prompt-input visible). " +
                        "Expected port " + expectedPort + ". url=" + page.url()
                        + " accessUrl=" + accessUrl);
        }
    }

    private static void waitForHttp(String url, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(1_000);
                conn.setReadTimeout(1_000);
                if (conn.getResponseCode() == 200) return;
            } catch (IOException ignored) {}
            Thread.sleep(500);
        }
        throw new AssertionError("Service did not respond at " + url + " within " + timeoutMs + "ms");
    }

    private static void verifyNotInSessions(int port) throws Exception {
        String status = E2EHttp.get(PORTAL_PORT, "/api/status");
        JsonNode root = MAPPER.readTree(status);
        for (JsonNode session : root.path("activeSessions")) {
            if (session.path("port").asInt() == port)
                throw new AssertionError(
                        "External process on port " + port + " must NOT appear in portal activeSessions, "
                        + "but found: " + session);
        }
    }
}
