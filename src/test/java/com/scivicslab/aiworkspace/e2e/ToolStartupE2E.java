package com.scivicslab.aiworkspace.e2e;

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
 * E2E: verifies that each tool starts via the quarkus-AI-workspace UI (Playwright)
 * and serves content in a browser tab.
 *
 * Flow per tool:
 *   1. Navigate to the quarkus-AI-workspace dashboard
 *   2. Fill launch parameters and click Launch
 *   3. Wait for the session card to reach READY (polling /api/status in browser context)
 *   4. Click the session link → verify the tool page content in the new tab
 *   5. Click Stop on the session card
 *
 * Run via AiWorkspaceE2ERunner.
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
        AiWorkspaceProcess portal = AiWorkspaceProcess.start(
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

            // The parameter form is collapsed until asked for, so that ten tools' worth of
            // forms are not all expanded at once (ControlDashboard_260905_oo01). Open this
            // tool's form before its fields can be filled.
            page.locator("#tool-tile-html-saurus .tool-buttons .btn-launch").click();

            page.locator("#param-html-saurus-dir")
                    .waitFor(new Locator.WaitForOptions().setTimeout(PAGE_TIMEOUT_MS));
            page.locator("#param-html-saurus-dir").fill(HOME + "/works");

            // launchTool() does fetch + location.reload() — wrap click with waitForNavigation
            page.waitForNavigation(
                    new Page.WaitForNavigationOptions().setWaitUntil(WaitUntilState.LOAD),
            // Scope the click to the form: the tile carries two .btn-launch buttons now, the
            // one that opens the form and the one inside it that starts the tool.
                    () -> page.locator("#launch-form-html-saurus .btn-launch").click());

            waitForReady(page, "html-saurus");

            // Click the session link (<a target="_blank">) → new tab
            Page toolPage = page.context().waitForPage(() ->
                    instanceRow(page, "html-saurus").locator("a.btn-open").click());
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

            // The parameter form is collapsed until asked for, so that ten tools' worth of
            // forms are not all expanded at once (ControlDashboard_260905_oo01). Open this
            // tool's form before its fields can be filled.
            page.locator("#tool-tile-quarkus-chat-ui .tool-buttons .btn-launch").click();

            page.locator("#param-quarkus-chat-ui-workdir")
                    .waitFor(new Locator.WaitForOptions().setTimeout(PAGE_TIMEOUT_MS));
            page.locator("#param-quarkus-chat-ui-workdir").fill(HOME + "/works");
            // provider select defaults to "claude" — no change needed

            page.waitForNavigation(
                    new Page.WaitForNavigationOptions().setWaitUntil(WaitUntilState.LOAD),
            // Scope the click to the form: the tile carries two .btn-launch buttons now, the
            // one that opens the form and the one inside it that starts the tool.
                    () -> page.locator("#launch-form-quarkus-chat-ui .btn-launch").click());

            waitForReady(page, "quarkus-chat-ui");

            Page toolPage = page.context().waitForPage(() ->
                    instanceRow(page, "quarkus-chat-ui").locator("a.btn-open").click());
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

            // The parameter form is collapsed until asked for, so that ten tools' worth of
            // forms are not all expanded at once (ControlDashboard_260905_oo01). Open this
            // tool's form before its fields can be filled.
            page.locator("#tool-tile-turing-workflow-editor .tool-buttons .btn-launch").click();

            page.locator("#param-turing-workflow-editor-workdir")
                    .waitFor(new Locator.WaitForOptions().setTimeout(PAGE_TIMEOUT_MS));
            page.locator("#param-turing-workflow-editor-workdir").fill(HOME + "/works");

            page.waitForNavigation(
                    new Page.WaitForNavigationOptions().setWaitUntil(WaitUntilState.LOAD),
            // Scope the click to the form: the tile carries two .btn-launch buttons now, the
            // one that opens the form and the one inside it that starts the tool.
                    () -> page.locator("#launch-form-turing-workflow-editor .btn-launch").click());

            waitForReady(page, "turing-workflow-editor");

            Page toolPage = page.context().waitForPage(() ->
                    instanceRow(page, "turing-workflow-editor").locator("a.btn-open").click());
            toolPage.waitForLoadState(LoadState.LOAD);

            // Name elements, not words: the chat UI also says "workflow" in its own text, and
            // opening the chat UI from this link is exactly the mistake being guarded against.
            //
            // Not #stepsContainer: it is an empty <div> until a workflow is loaded, so it has no
            // box and never counts as visible. Not #runBtn: this editor has no element by that
            // name — running is driven from #paramExecute inside the side panel, which is closed
            // to begin with. The File menu button and the description box are on screen as soon
            // as the page is drawn.
            if (!toolPage.locator("#fileMenuBtn").isVisible())
                throw new AssertionError(
                    "turing-workflow-editor: dashboard link must open the workflow editor " +
                    "(#fileMenuBtn not visible — chat UI or wrong page opened instead). " +
                    "url=" + toolPage.url());
            if (!toolPage.locator("#workflowDescription").isVisible())
                throw new AssertionError(
                    "turing-workflow-editor: #workflowDescription not visible. url=" + toolPage.url());
            if (toolPage.locator("#prompt-input").isVisible())
                throw new AssertionError(
                    "turing-workflow-editor: dashboard link opened chat UI (#prompt-input visible). " +
                    "url=" + toolPage.url());
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
        // Running instances moved off the catalog onto their own page, as a table with one row
        // per instance keyed by data-tool and data-port (ControlDashboard_260905_oo01).
        page.navigate(java.net.URI.create(page.url()).resolve("/instances").toString(),
                new Page.NavigateOptions().setWaitUntil(WaitUntilState.LOAD));
    }

    /** The Instances table row for one tool, whichever port it was given. */
    private Locator instanceRow(Page page, String toolName) {
        return page.locator("tr[data-tool='" + toolName + "']").first();
    }

    private void stopSession(Page page, String toolName) {
        Locator row = instanceRow(page, toolName);
        row.waitFor(new Locator.WaitForOptions().setTimeout(PAGE_TIMEOUT_MS));
        row.locator("button.btn-stop").click();
        // stopTool() in app.js removes the row once the server confirms the stop
        row.waitFor(new Locator.WaitForOptions()
                .setTimeout(PAGE_TIMEOUT_MS)
                .setState(WaitForSelectorState.DETACHED));
    }
}
