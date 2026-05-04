package com.scivicslab.serviceportal.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;

import java.nio.file.Path;
import java.util.Map;

/**
 * E2E: verifies that each tool starts via the service-portal UI (Playwright)
 * and serves content in a browser tab.
 *
 * Flow per tool:
 *   1. Navigate to the service-portal dashboard
 *   2. Fill launch parameters and click Launch
 *   3. Wait for the session card to reach READY (polling /api/status in browser context)
 *   4. Click the session link → verify the tool page content in the new tab
 *   5. Click Stop on the session card
 *
 * Run via ServicePortalE2ERunner.
 */
class ToolStartupE2E {

    private static final int POLL_TIMEOUT_MS = 60_000;
    private static final int PAGE_TIMEOUT_MS = 15_000;
    private static final String HOME = System.getProperty("user.home");

    void run() throws Exception {
        System.out.println("--- ToolStartupE2E ---");
        Path configPath  = E2EConfig.configYaml();
        Path testJarsDir = E2EConfig.testJarsDir();

        int portalPort = E2EConfig.findFreePortBase(20);
        ServicePortalProcess portal = ServicePortalProcess.start(
                configPath, portalPort, Map.of("TEST_JARS_DIR", testJarsDir.toString()));
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true));
            try {
                testHtmlSaurus(portalPort, browser);
                testChatUi(portalPort, browser);
                testTuringWorkflowEditor(portalPort, browser);
            } finally {
                browser.close();
                portal.stop();
            }
        }
        System.out.println("ToolStartupE2E: PASSED");
    }

    private void testHtmlSaurus(int portalPort, Browser browser) throws Exception {
        System.out.println("  html-saurus: launching...");
        try (Page page = browser.newPage()) {
            navigateToDashboard(page, portalPort);

            page.locator("#param-html-saurus-dir")
                    .waitFor(new Locator.WaitForOptions().setTimeout(PAGE_TIMEOUT_MS));
            page.locator("#param-html-saurus-dir").fill(HOME + "/works");

            // launchTool() does fetch + location.reload() — wrap click with waitForNavigation
            page.waitForNavigation(
                    new Page.WaitForNavigationOptions().setWaitUntil(WaitUntilState.LOAD),
                    () -> page.locator("#tool-tile-html-saurus .btn-launch").click());

            waitForReady(page, "html-saurus");

            // Click the session link (<a target="_blank">) → new tab
            Page toolPage = page.context().waitForPage(() ->
                    page.locator("[id^='session-html-saurus-'] a.session-name").first().click());
            toolPage.waitForLoadState(LoadState.LOAD);

            String body = toolPage.textContent("body");
            if (body == null || !body.contains("doc_"))
                throw new AssertionError("html-saurus: page should list doc_ projects");
            toolPage.close();

            stopSession(page, "html-saurus");
        }
        System.out.println("  html-saurus: PASSED");
    }

    private void testChatUi(int portalPort, Browser browser) throws Exception {
        System.out.println("  quarkus-chat-ui: launching...");
        try (Page page = browser.newPage()) {
            navigateToDashboard(page, portalPort);

            page.locator("#param-quarkus-chat-ui-workdir")
                    .waitFor(new Locator.WaitForOptions().setTimeout(PAGE_TIMEOUT_MS));
            page.locator("#param-quarkus-chat-ui-workdir").fill(HOME + "/works");
            // provider select defaults to "claude" — no change needed

            page.waitForNavigation(
                    new Page.WaitForNavigationOptions().setWaitUntil(WaitUntilState.LOAD),
                    () -> page.locator("#tool-tile-quarkus-chat-ui .btn-launch").click());

            waitForReady(page, "quarkus-chat-ui");

            Page toolPage = page.context().waitForPage(() ->
                    page.locator("[id^='session-quarkus-chat-ui-'] a.session-name").first().click());
            toolPage.waitForLoadState(LoadState.LOAD);

            String body = toolPage.textContent("body");
            if (body == null || (!body.toLowerCase().contains("chat")
                    && !body.toLowerCase().contains("message")
                    && !body.toLowerCase().contains("provider")))
                throw new AssertionError("chat-ui: page missing expected content");
            toolPage.close();

            stopSession(page, "quarkus-chat-ui");
        }
        System.out.println("  quarkus-chat-ui: PASSED");
    }

    private void testTuringWorkflowEditor(int portalPort, Browser browser) throws Exception {
        System.out.println("  turing-workflow-editor: launching...");
        try (Page page = browser.newPage()) {
            navigateToDashboard(page, portalPort);

            page.locator("#param-turing-workflow-editor-workdir")
                    .waitFor(new Locator.WaitForOptions().setTimeout(PAGE_TIMEOUT_MS));
            page.locator("#param-turing-workflow-editor-workdir").fill(HOME + "/works");

            page.waitForNavigation(
                    new Page.WaitForNavigationOptions().setWaitUntil(WaitUntilState.LOAD),
                    () -> page.locator("#tool-tile-turing-workflow-editor .btn-launch").click());

            waitForReady(page, "turing-workflow-editor");

            Page toolPage = page.context().waitForPage(() ->
                    page.locator("[id^='session-turing-workflow-editor-'] a.session-name").first().click());
            toolPage.waitForLoadState(LoadState.LOAD);

            String body = toolPage.textContent("body");
            if (body == null || (!body.toLowerCase().contains("workflow")
                    && !body.toLowerCase().contains("editor")))
                throw new AssertionError("turing-workflow-editor: page missing expected content");
            toolPage.close();

            stopSession(page, "turing-workflow-editor");
        }
        System.out.println("  turing-workflow-editor: PASSED");
    }

    private void navigateToDashboard(Page page, int portalPort) {
        page.navigate("http://localhost:" + portalPort + "/",
                new Page.NavigateOptions().setWaitUntil(WaitUntilState.LOAD));
    }

    private void waitForReady(Page page, String toolName) {
        page.waitForFunction(
                "async (name) => {" +
                "  try {" +
                "    const r = await fetch('api/status');" +
                "    if (!r.ok) return false;" +
                "    const d = await r.json();" +
                "    const all = [...(d.activeSessions || []), ...(d.managementServices || [])];" +
                "    return all.some(s => s.toolName === name && s.state === 'READY');" +
                "  } catch(e) { return false; }" +
                "}",
                toolName,
                new Page.WaitForFunctionOptions()
                        .setTimeout(POLL_TIMEOUT_MS)
                        .setPollingInterval(2_000));
        // Reload so the server renders the <a> session link (accessUrl now set)
        page.reload(new Page.ReloadOptions().setWaitUntil(WaitUntilState.LOAD));
    }

    private void stopSession(Page page, String toolName) {
        Locator stopBtn = page.locator(
                "[id^='session-" + toolName + "-'] button.btn-stop").first();
        stopBtn.waitFor(new Locator.WaitForOptions().setTimeout(PAGE_TIMEOUT_MS));
        stopBtn.click();
        // stopSession() in app.js removes the card from DOM on success
        page.locator("[id^='session-" + toolName + "-']").first()
                .waitFor(new Locator.WaitForOptions()
                        .setTimeout(PAGE_TIMEOUT_MS)
                        .setState(WaitForSelectorState.DETACHED));
    }
}
