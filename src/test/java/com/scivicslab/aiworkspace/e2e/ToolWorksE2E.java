package com.scivicslab.aiworkspace.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * E2E: verifies that each tool actually works — not just starts — by checking
 * functional UI elements and API responses via Playwright and HTTP.
 *
 * Scenarios:
 *   1. chat-ui: #prompt-input visible and enabled
 *   2. html-saurus: portal lists doc_ projects; clicking a project link opens an actual document page
 *   3. turing-workflow-editor: #fileMenuBtn and #workflowDescription are visible
 *
 * Run via AiWorkspaceE2ERunner.
 */
class ToolWorksE2E {

    private static final int POLL_TIMEOUT_MS = 60_000;
    private static final String HOME = System.getProperty("user.home");

    void run() throws Exception {
        System.out.println("--- ToolWorksE2E ---");
        Path configPath  = E2EConfig.configYaml();
        Path testJarsDir = E2EConfig.testJarsDir();

        int portalPort = E2EConfig.findFreePortBase(20);
        AiWorkspaceProcess portal = AiWorkspaceProcess.start(
                configPath, portalPort, Map.of("TEST_JARS_DIR", testJarsDir.toString()));
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true));
            try {
                scenarioChatUi(portal, browser);
                scenarioHtmlSaurus(portal.port(), browser);
                scenarioTuringWorkflowEditor(portal.port(), browser);
            } finally {
                browser.close();
                portal.stop();
            }
        }
        System.out.println("ToolWorksE2E: PASSED");
    }

    // Scenarios 1+2: chat-ui prompt input usable; launched with correct MCP URL
    private void scenarioChatUi(AiWorkspaceProcess portal, Browser browser) throws Exception {
        int portalPort = portal.port();
        System.out.println("  [chat-ui] launching...");
        E2EHttp.post(portalPort, "/api/tool/quarkus-chat-ui/launch",
                Map.of("provider", "claude", "workdir", HOME + "/works"));
        int chatUiPort = E2EHttp.waitForToolReady(portalPort, "quarkus-chat-ui", POLL_TIMEOUT_MS);

        try (Page page = browser.newPage()) {
            page.navigate("http://localhost:" + chatUiPort,
                    new Page.NavigateOptions().setWaitUntil(WaitUntilState.LOAD));
            page.locator("#app").waitFor(new Locator.WaitForOptions().setTimeout(10_000));

            if (!page.locator("#prompt-input").isVisible())
                throw new AssertionError("chat-ui: #prompt-input must be visible");
            if (!page.locator("#prompt-input").isEnabled())
                throw new AssertionError("chat-ui: #prompt-input must be enabled");
            if (page.locator("#login-screen").isVisible())
                throw new AssertionError("chat-ui: #login-screen must be hidden in single-user mode");
        }

        // A second scenario used to read AI-workspace's own log and require that chat-ui had
        // been launched with -Dchat-ui.agent-loop.mcp-urls pointing at its own /mcp endpoint.
        // The MCP gateway was removed in 32487fb and nothing sets that property any more, so
        // the check asked for something the product no longer does. The three tests that
        // reached a tool through the gateway were rewritten in 4f3aab2; this one was missed.

        E2EHttp.post(portalPort, "/api/tool/quarkus-chat-ui/" + chatUiPort + "/stop", Map.of());
        E2EHttp.waitForToolStopped(portalPort, "quarkus-chat-ui", 10_000);
        System.out.println("  [chat-ui] PASSED (prompt usable)");
    }

    // Scenario 3: html-saurus portal lists projects; clicking a project link opens an actual document page
    private void scenarioHtmlSaurus(int portalPort, Browser browser) throws Exception {
        System.out.println("  [html-saurus] launching...");
        E2EHttp.post(portalPort, "/api/tool/html-saurus/launch",
                Map.of("dir", HOME + "/works"));
        int htmlSaurusPort = E2EHttp.waitForToolReady(portalPort, "html-saurus", POLL_TIMEOUT_MS);

        try (Page page = browser.newPage()) {
            page.navigate("http://localhost:" + htmlSaurusPort,
                    new Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));

            Locator docLinks = page.locator("a:has-text('doc_')");
            if (docLinks.count() == 0)
                throw new AssertionError("html-saurus portal: no doc_ project links found");

            docLinks.first().click();
            page.waitForLoadState(LoadState.NETWORKIDLE);

            String title = page.title();
            if (title == null || title.isBlank())
                throw new AssertionError("html-saurus: document page title is empty");
            String bodyText = page.textContent("body");
            if (bodyText == null || bodyText.isBlank())
                throw new AssertionError("html-saurus: document page body has no text content");
        }

        E2EHttp.post(portalPort, "/api/tool/html-saurus/" + htmlSaurusPort + "/stop", Map.of());
        E2EHttp.waitForToolStopped(portalPort, "html-saurus", 10_000);
        System.out.println("  [html-saurus] PASSED (portal lists projects; document page opens)");
    }

    // Scenario 4: turing-workflow-editor shows the editor canvas and run button
    private void scenarioTuringWorkflowEditor(int portalPort, Browser browser) throws Exception {
        System.out.println("  [turing-workflow-editor] launching...");
        E2EHttp.post(portalPort, "/api/tool/turing-workflow-editor/launch",
                Map.of("workdir", HOME + "/works"));
        int editorPort = E2EHttp.waitForToolReady(portalPort, "turing-workflow-editor", POLL_TIMEOUT_MS);

        try (Page page = browser.newPage()) {
            page.navigate("http://localhost:" + editorPort,
                    new Page.NavigateOptions().setWaitUntil(WaitUntilState.LOAD));

            // Not #stepsContainer: it is an empty <div> until a workflow is loaded, so it has
            // no box and never counts as visible. Not #runBtn: this editor has no element by
            // that name — running is driven from #paramExecute inside the side panel, which is
            // closed to begin with.
            if (!page.locator("#fileMenuBtn").isVisible())
                throw new AssertionError("turing-workflow-editor: #fileMenuBtn must be visible");
            if (!page.locator("#workflowDescription").isVisible())
                throw new AssertionError("turing-workflow-editor: #workflowDescription must be visible");
        }

        E2EHttp.post(portalPort, "/api/tool/turing-workflow-editor/" + editorPort + "/stop", Map.of());
        E2EHttp.waitForToolStopped(portalPort, "turing-workflow-editor", 10_000);
        System.out.println("  [turing-workflow-editor] PASSED (file menu and description box visible)");
    }
}
