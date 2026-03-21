package com.scivicslab.lxdpups.e2e;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.RequestOptions;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Playwright-based E2E tests for the child portal dashboard.
 * Run with: mvn verify -Dportal.url=http://CONTAINER_IP:16080
 * or set PORTAL_URL env var. Falls back to auto-detecting via lxc list.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class DashboardIT {

    private static Playwright playwright;
    private static Browser browser;
    private static String portalUrl;

    private BrowserContext context;
    private Page page;

    @BeforeAll
    static void setup() {
        portalUrl = PortalUrlResolver.resolve();
        System.out.println("Portal URL: " + portalUrl);
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
    }

    @AfterAll
    static void teardown() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    @BeforeEach
    void createContext() {
        context = browser.newContext();
        page = context.newPage();
    }

    @AfterEach
    void closeContext() {
        if (context != null) context.close();
    }

    // ---- Page structure ----

    @Test
    @Order(1)
    void pageLoadsWithCorrectTitle() {
        page.navigate(portalUrl);
        assertThat(page).hasTitle(java.util.regex.Pattern.compile("AI Worker"));
    }

    @Test
    @Order(2)
    void showsAvailableToolsSection() {
        page.navigate(portalUrl);
        assertThat(page.locator("text=Available Tools")).isVisible();
    }

    @Test
    @Order(3)
    void showsAll7ToolTiles() {
        page.navigate(portalUrl);
        var tiles = page.locator(".tool-tile");
        assertThat(tiles).hasCount(7);
    }

    @Test
    @Order(4)
    void eachToolTileHasNewLink() {
        page.navigate(portalUrl);
        var newLinks = page.locator(".tool-action", new Page.LocatorOptions().setHasText("+ New"));
        assertThat(newLinks).hasCount(7);
    }

    // ---- Build links ----

    @Test
    @Order(10)
    void buildLinkAppearsForToolsWithBuildDir() {
        page.navigate(portalUrl);
        var buildLinks = page.locator(".tool-action", new Page.LocatorOptions().setHasText("Build"));
        // 4 tools have build-dir: llm-console-local, claude-code, codex, workflow-editor
        assertThat(buildLinks).hasCount(4);
    }

    @Test
    @Order(11)
    void buildLinkDoesNotAppearForDocusaurus() {
        page.navigate(portalUrl);
        var docTile = page.locator("#tool-tile-docusaurus");
        assertThat(docTile).isVisible();
        var buildInDoc = docTile.locator(".tool-action", new Locator.LocatorOptions().setHasText("Build"));
        assertThat(buildInDoc).hasCount(0);
    }

    @Test
    @Order(12)
    void buildLinkAppearsForLlmConsoleLocal() {
        page.navigate(portalUrl);
        var tile = page.locator("#tool-tile-llm-console-local");
        assertThat(tile).isVisible();
        var build = tile.locator(".tool-action", new Locator.LocatorOptions().setHasText("Build"));
        assertThat(build).hasCount(1);
    }

    @Test
    @Order(13)
    void buildLinkAppearsForClaudeCode() {
        page.navigate(portalUrl);
        var tile = page.locator("#tool-tile-claude-code");
        var build = tile.locator(".tool-action", new Locator.LocatorOptions().setHasText("Build"));
        assertThat(build).hasCount(1);
    }

    @Test
    @Order(14)
    void buildLinkAppearsForCodex() {
        page.navigate(portalUrl);
        var tile = page.locator("#tool-tile-codex");
        var build = tile.locator(".tool-action", new Locator.LocatorOptions().setHasText("Build"));
        assertThat(build).hasCount(1);
    }

    @Test
    @Order(15)
    void buildLinkAppearsForWorkflowEditor() {
        page.navigate(portalUrl);
        var tile = page.locator("#tool-tile-workflow-editor");
        var build = tile.locator(".tool-action", new Locator.LocatorOptions().setHasText("Build"));
        assertThat(build).hasCount(1);
    }

    // ---- Build dialog ----

    @Test
    @Order(20)
    void clickingBuildShowsConfirmDialog() {
        page.navigate(portalUrl);
        final String[] dialogMessage = {""};
        page.onDialog(dialog -> {
            dialogMessage[0] = dialog.message();
            dialog.dismiss();
        });
        var tile = page.locator("#tool-tile-llm-console-local");
        var buildLink = tile.locator(".tool-action", new Locator.LocatorOptions().setHasText("Build"));
        buildLink.click();
        assertTrue(dialogMessage[0].contains("Build"), "Dialog should mention Build");
        assertTrue(dialogMessage[0].contains("llm-console-local"), "Dialog should mention tool name");
    }

    // ---- Build API ----

    @Test
    @Order(30)
    void buildApiReturns202ForBuildableTool() {
        var apiCtx = playwright.request().newContext();
        var resp = apiCtx.post(portalUrl + "/api/tools/llm-console-local/build");
        // 202 Accepted or 409 Conflict (already building) are both valid
        assertTrue(List.of(202, 409).contains(resp.status()),
                "Expected 202 or 409, got " + resp.status());
        apiCtx.dispose();
    }

    @Test
    @Order(31)
    void buildApiReturns400ForNonBuildableTool() {
        var apiCtx = playwright.request().newContext();
        var resp = apiCtx.post(portalUrl + "/api/tools/docusaurus/build");
        assertEquals(400, resp.status());
        var body = resp.text();
        assertTrue(body.contains("does not support building"),
                "Response should indicate tool does not support building");
        apiCtx.dispose();
    }

    @Test
    @Order(32)
    void buildProgressApiReturnsValidJson() {
        var apiCtx = playwright.request().newContext();
        var resp = apiCtx.get(portalUrl + "/api/tools/llm-console-local/build/progress");
        assertEquals(200, resp.status());
        var body = resp.text();
        assertTrue(body.contains("\"name\""), "Should contain name field");
        assertTrue(body.contains("\"phase\""), "Should contain phase field");
        assertTrue(body.contains("\"messages\""), "Should contain messages field");
        assertTrue(body.contains("\"done\""), "Should contain done field");
        assertTrue(body.contains("\"success\""), "Should contain success field");
        apiCtx.dispose();
    }

    // ---- Sections ----

    @Test
    @Order(40)
    void managementServicesSectionIsVisible() {
        page.navigate(portalUrl);
        assertThat(page.locator("text=Management Services")).isVisible();
    }

    @Test
    @Order(41)
    void runningServicesSectionExists() {
        page.navigate(portalUrl);
        assertThat(page.getByText("Running Services", new Page.GetByTextOptions().setExact(true))).isVisible();
    }

    @Test
    @Order(42)
    void buildProgressLogContainerExistsForEachBuildableTool() {
        page.navigate(portalUrl);
        for (var name : List.of("llm-console-local", "claude-code", "codex", "workflow-editor")) {
            var logEl = page.locator("#build-log-" + name);
            assertThat(logEl).isAttached();
        }
    }

    // ---- Docusaurus project picker ----

    @Test
    @Order(50)
    void docusaurusProjectsApiReturnsList() {
        var apiCtx = playwright.request().newContext();
        var resp = apiCtx.get(portalUrl + "/api/tools/docusaurus/projects");
        assertEquals(200, resp.status());
        var body = resp.text();
        assertTrue(body.startsWith("["), "Should return JSON array");
        assertTrue(body.contains("\"name\""), "Each project should have name");
        assertTrue(body.contains("\"path\""), "Each project should have path");
        apiCtx.dispose();
    }

    @Test
    @Order(51)
    void clickingDocusaurusNewShowsProjectPickerModal() {
        page.navigate(portalUrl);
        var docTile = page.locator("#tool-tile-docusaurus");
        var newLink = docTile.locator(".tool-action", new Locator.LocatorOptions().setHasText("+ New"));
        newLink.click();

        var modal = page.locator("#docusaurus-modal");
        assertThat(modal).isVisible();
        assertThat(modal.locator("text=Select Docusaurus Project")).isVisible();

        // Should have at least one project item
        var items = modal.locator("div[onclick*='launchDocusaurus']");
        assertTrue(items.count() > 0, "Modal should list at least one project");
    }

    @Test
    @Order(52)
    void docusaurusModalCanBeClosedWithCancel() {
        page.navigate(portalUrl);
        var docTile = page.locator("#tool-tile-docusaurus");
        docTile.locator(".tool-action", new Locator.LocatorOptions().setHasText("+ New")).click();

        var modal = page.locator("#docusaurus-modal");
        assertThat(modal).isVisible();
        modal.locator("button", new Locator.LocatorOptions().setHasText("Cancel")).click();
        assertThat(modal).not().isVisible();
    }

    @Test
    @Order(53)
    void docusaurusLaunchApiAcceptsWorkDirParameter() {
        var apiCtx = playwright.request().newContext();
        var resp = apiCtx.post(portalUrl + "/api/tools/docusaurus/launch",
                RequestOptions.create()
                        .setHeader("Content-Type", "application/json")
                        .setData(Map.of("workDir", "/home/devteam/works/doc_SCIVICS003")));
        assertEquals(200, resp.status());
        var body = resp.text();
        assertTrue(body.contains("\"launched\""), "Should indicate launched status");
        assertTrue(body.contains("\"docusaurus\""), "Should indicate tool name");

        // Extract port and clean up
        var portMatch = java.util.regex.Pattern.compile("\"port\"\\s*:\\s*(\\d+)").matcher(body);
        if (portMatch.find()) {
            int port = Integer.parseInt(portMatch.group(1));
            assertTrue(port >= 16300, "Port should be in docusaurus range");
            apiCtx.post(portalUrl + "/api/tools/docusaurus/stop?port=" + port);
        }
        apiCtx.dispose();
    }

    // ---- Docusaurus full flow: modal click -> launch -> visible in Running Services ----

    @Test
    @Order(60)
    void clickingProjectInModalTriggersLaunchApi() {
        page.navigate(portalUrl);

        // Open modal first
        var docTile = page.locator("#tool-tile-docusaurus");
        docTile.locator(".tool-action", new Locator.LocatorOptions().setHasText("+ New")).click();
        var modal = page.locator("#docusaurus-modal");
        assertThat(modal).isVisible();

        var firstProject = modal.locator("div[onclick*='launchDocusaurus']").first();

        // waitForRequest takes a Predicate and a Runnable (the action that triggers the request)
        var req = page.waitForRequest(
                r -> r.url().contains("/api/tools/docusaurus/launch") && "POST".equals(r.method()),
                () -> firstProject.click()
        );

        // Verify the fetch was made with correct payload
        assertNotNull(req, "Launch request should have been sent");
        var postData = req.postData();
        assertNotNull(postData, "POST data should not be null");
        assertTrue(postData.contains("workDir"), "POST data should contain workDir");
        assertTrue(postData.contains("/home/devteam/works/"), "workDir should point to works directory");

        // Clean up launched instance
        page.waitForLoadState();
        cleanupDocusaurusInstances();
    }

    @Test
    @Order(61)
    void clickingProjectInModalLaunchesInstance() {
        // Make sure no docusaurus instances are running
        cleanupDocusaurusInstances();

        page.navigate(portalUrl);

        // Open the docusaurus project picker modal
        var docTile = page.locator("#tool-tile-docusaurus");
        docTile.locator(".tool-action", new Locator.LocatorOptions().setHasText("+ New")).click();
        var modal = page.locator("#docusaurus-modal");
        assertThat(modal).isVisible();

        // Click the first project in the list
        var firstProject = modal.locator("div[onclick*='launchDocusaurus']").first();
        firstProject.click();

        // Modal should disappear (removed on click)
        assertThat(modal).not().isVisible();

        // Page reloads after launch - wait for it
        page.waitForLoadState();

        // Verify via API that an instance was launched
        var apiCtx = playwright.request().newContext();
        var resp = apiCtx.get(portalUrl + "/api/tools/instances");
        assertEquals(200, resp.status());
        var body = resp.text();
        assertTrue(body.contains("docusaurus") || body.contains("163"),
                "Tool instances should contain a docusaurus entry");
        apiCtx.dispose();

        // Clean up
        cleanupDocusaurusInstances();
    }

    // ---- Doc Build & Index / Doc Search tool tiles ----

    @Test
    @Order(70)
    void docBuildIndexTileIsVisible() {
        page.navigate(portalUrl);
        var tile = page.locator("#tool-tile-doc-build-index");
        assertThat(tile).isVisible();
        assertThat(tile.locator(".tool-name")).hasText("Doc Build & Index");
    }

    @Test
    @Order(71)
    void docSearchTileIsVisible() {
        page.navigate(portalUrl);
        var tile = page.locator("#tool-tile-doc-search");
        assertThat(tile).isVisible();
        assertThat(tile.locator(".tool-name")).hasText("Doc Search");
    }

    @Test
    @Order(72)
    void docBuildIndexHasNewLinkButNoBuild() {
        page.navigate(portalUrl);
        var tile = page.locator("#tool-tile-doc-build-index");
        var newLink = tile.locator(".tool-action", new Locator.LocatorOptions().setHasText("+ New"));
        assertThat(newLink).hasCount(1);
        var buildLink = tile.locator(".tool-action", new Locator.LocatorOptions().setHasText("Build"));
        assertThat(buildLink).hasCount(0);
    }

    @Test
    @Order(73)
    void docSearchHasNewLinkButNoBuild() {
        page.navigate(portalUrl);
        var tile = page.locator("#tool-tile-doc-search");
        var newLink = tile.locator(".tool-action", new Locator.LocatorOptions().setHasText("+ New"));
        assertThat(newLink).hasCount(1);
        var buildLink = tile.locator(".tool-action", new Locator.LocatorOptions().setHasText("Build"));
        assertThat(buildLink).hasCount(0);
    }

    @Test
    @Order(74)
    void docBuildIndexLaunchApiWorks() {
        var apiCtx = playwright.request().newContext();
        var resp = apiCtx.post(portalUrl + "/api/tools/doc-build-index/launch",
                RequestOptions.create()
                        .setHeader("Content-Type", "application/json")
                        .setData(Map.of()));
        assertEquals(200, resp.status());
        var body = resp.text();
        assertTrue(body.contains("\"launched\""), "Should indicate launched status");
        assertTrue(body.contains("\"doc-build-index\""), "Should indicate tool name");

        // Extract port and clean up
        var portMatch = java.util.regex.Pattern.compile("\"port\"\\s*:\\s*(\\d+)").matcher(body);
        if (portMatch.find()) {
            int port = Integer.parseInt(portMatch.group(1));
            assertTrue(port >= 16320 && port <= 16329, "Port should be in doc-build-index range");
            apiCtx.post(portalUrl + "/api/tools/doc-build-index/stop?port=" + port);
        }
        apiCtx.dispose();
    }

    @Test
    @Order(75)
    void docSearchLaunchApiWorks() {
        var apiCtx = playwright.request().newContext();
        var resp = apiCtx.post(portalUrl + "/api/tools/doc-search/launch",
                RequestOptions.create()
                        .setHeader("Content-Type", "application/json")
                        .setData(Map.of()));
        assertEquals(200, resp.status());
        var body = resp.text();
        assertTrue(body.contains("\"launched\""), "Should indicate launched status");
        assertTrue(body.contains("\"doc-search\""), "Should indicate tool name");

        // Extract port and clean up
        var portMatch = java.util.regex.Pattern.compile("\"port\"\\s*:\\s*(\\d+)").matcher(body);
        if (portMatch.find()) {
            int port = Integer.parseInt(portMatch.group(1));
            assertTrue(port >= 16330 && port <= 16339, "Port should be in doc-search range");
            apiCtx.post(portalUrl + "/api/tools/doc-search/stop?port=" + port);
        }
        apiCtx.dispose();
    }

    private void cleanupDocusaurusInstances() {
        var apiCtx = playwright.request().newContext();
        for (int port = 16300; port <= 16319; port++) {
            try {
                apiCtx.post(portalUrl + "/api/tools/docusaurus/stop?port=" + port);
            } catch (Exception ignored) {
            }
        }
        apiCtx.dispose();
    }
}
