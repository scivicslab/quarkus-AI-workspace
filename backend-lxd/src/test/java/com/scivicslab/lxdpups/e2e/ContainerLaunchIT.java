package com.scivicslab.lxdpups.e2e;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.RequestOptions;
import org.junit.jupiter.api.*;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E tests that verify the full container launch flow:
 * click Launch -> container becomes RUNNING -> service is accessible at its port.
 *
 * Run with: mvn verify -Dhost.portal.url=http://localhost:16080
 * Each test launches a real LXC container and cleans it up after.
 *
 * These tests exist to catch regressions where a change breaks the launch pipeline
 * (e.g. service file User= mismatch, missing provisioning step, port not reachable).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ContainerLaunchIT {

    private static final String DEFAULT_URL = "http://localhost:16080";
    private static final int LAUNCH_TIMEOUT_MS = 5 * 60 * 1000; // 5 minutes
    private static final int POLL_INTERVAL_MS = 5000;
    private static final Pattern IP_PATTERN = Pattern.compile("Container IP: (\\d+\\.\\d+\\.\\d+\\.\\d+)");

    private static Playwright playwright;
    private static Browser browser;
    private static String baseUrl;
    private static APIRequestContext api;

    @BeforeAll
    static void setup() {
        baseUrl = resolveUrl();
        System.out.println("Host portal URL: " + baseUrl);
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        api = playwright.request().newContext(new APIRequest.NewContextOptions().setBaseURL(baseUrl));
    }

    @AfterAll
    static void teardown() {
        if (api != null) api.dispose();
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    // ── Jupyter Lab ──────────────────────────────────────────────────────────

    @Test
    @Order(100)
    void jupyterContainerLaunchesAndUserScreenAppears() throws InterruptedException {
        String name = "e2e-jupyter-" + Long.toHexString(System.currentTimeMillis());
        var page = browser.newPage();
        try {
            // 1. Open parent portal and click Launch on the Jupyter card
            page.navigate(baseUrl);
            var jupyterCard = page.locator(".launch-card", new Page.LocatorOptions().setHasText("Jupyter"));
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(jupyterCard).isVisible();

            // Intercept the API call so we can capture the container name used by the UI
            final String[] launchedName = {name};
            page.onRequest(req -> {
                if (req.url().contains("/api/containers") && "POST".equals(req.method())) {
                    String body = req.postData();
                    if (body != null) {
                        Matcher m = Pattern.compile("\"name\":\"([^\"]+)\"").matcher(body);
                        if (m.find()) launchedName[0] = m.group(1);
                    }
                }
            });
            jupyterCard.locator("button:has-text('Launch')").click();

            // 2. Wait for launch to complete (poll progress API)
            String progressJson = waitUntilDone(launchedName[0]);
            assertTrue(progressJson.contains("\"success\":true"),
                    "Jupyter launch should succeed. Progress: " + progressJson);
            name = launchedName[0];

            // 3. Get container IP
            String ip = extractIp(progressJson);
            assertNotNull(ip, "Container IP should be present in progress messages");
            String jupyterUrl = "http://" + ip + ":16900/";

            // 4. Navigate to Jupyter Lab URL in the browser
            page.navigate(jupyterUrl);
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions().setTimeout(30000));

            // 5. Verify the JupyterLab UI is displayed
            // JupyterLab renders a div#main or the page title contains "JupyterLab"
            var title = page.title();
            var content = page.content();
            assertTrue(
                title.contains("JupyterLab") || content.contains("jp-MainAreaWidget")
                        || content.contains("jupyter") || content.contains("JupyterLab"),
                "JupyterLab user screen should be visible. Title was: " + title
            );
            System.out.println("Jupyter title: " + title);

        } finally {
            page.close();
            stopContainer(name);
        }
    }

    // ── Remote Desktop (Guacamole) ────────────────────────────────────────────

    @Test
    @Order(110)
    void guacamoleContainerLaunchesAndUserScreenAppears() throws InterruptedException {
        String name = "e2e-desktop-" + Long.toHexString(System.currentTimeMillis());
        var page = browser.newPage();
        try {
            // 1. Open parent portal and click Launch on the Remote Desktop card
            page.navigate(baseUrl);
            var desktopCard = page.locator(".launch-card", new Page.LocatorOptions().setHasText("Remote Desktop"));
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(desktopCard).isVisible();

            final String[] launchedName = {name};
            page.onRequest(req -> {
                if (req.url().contains("/api/containers") && "POST".equals(req.method())) {
                    String body = req.postData();
                    if (body != null) {
                        Matcher m = Pattern.compile("\"name\":\"([^\"]+)\"").matcher(body);
                        if (m.find()) launchedName[0] = m.group(1);
                    }
                }
            });
            desktopCard.locator("button:has-text('Launch')").click();

            // 2. Wait for launch to complete
            String progressJson = waitUntilDone(launchedName[0]);
            assertTrue(progressJson.contains("\"success\":true"),
                    "Guacamole launch should succeed. Progress: " + progressJson);
            name = launchedName[0];

            // 3. Get container IP
            String ip = extractIp(progressJson);
            assertNotNull(ip, "Container IP should be present in progress messages");
            String guacUrl = "http://" + ip + ":16901/";

            // 4. Navigate to Guacamole URL in the browser
            page.navigate(guacUrl);
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions().setTimeout(30000));

            // 5. Verify the Guacamole UI is displayed.
            // The app may show a login form (awaitingCredentials) or jump straight to the
            // connection list if auto-login succeeds. Either way the Angular SPA loads.
            var title = page.title();
            var content = page.content();
            assertTrue(
                content.contains("guac-login") || content.contains("guacamole")
                        || content.contains("auto-login") || title.contains("Guacamole"),
                "Guacamole user screen should be visible. Title was: " + title
            );
            System.out.println("Guacamole title: " + title);

        } finally {
            page.close();
            stopContainer(name);
        }
    }

    // ── UI: Launch buttons exist ──────────────────────────────────────────────

    @Test
    @Order(10)
    void dashboardShowsJupyterLaunchButton() {
        var page = browser.newPage();
        try {
            page.navigate(baseUrl);
            var btn = page.locator("button:has-text('Launch')")
                    .filter(new Locator.FilterOptions().setHasText("Launch"));
            // Find the one near "Jupyter"
            var jupyterCard = page.locator(".launch-card", new Page.LocatorOptions().setHasText("Jupyter"));
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(jupyterCard).isVisible();
            var launchBtn = jupyterCard.locator("button:has-text('Launch')");
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(launchBtn).isVisible();
        } finally {
            page.close();
        }
    }

    @Test
    @Order(11)
    void dashboardShowsRemoteDesktopLaunchButton() {
        var page = browser.newPage();
        try {
            page.navigate(baseUrl);
            var desktopCard = page.locator(".launch-card", new Page.LocatorOptions().setHasText("Remote Desktop"));
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(desktopCard).isVisible();
            var launchBtn = desktopCard.locator("button:has-text('Launch')");
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(launchBtn).isVisible();
        } finally {
            page.close();
        }
    }

    // ── UI: Other portal boxes ────────────────────────────────────────────────

    @Test
    @Order(20)
    void dashboardShowsLlmConsolePortalBox() {
        var page = browser.newPage();
        try {
            page.navigate(baseUrl);
            var card = page.locator(".launch-card", new Page.LocatorOptions().setHasText("LLM Console Portal"));
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(card).isVisible();
        } finally {
            page.close();
        }
    }

    @Test
    @Order(21)
    void dashboardShowsWorkflowPortalBox() {
        var page = browser.newPage();
        try {
            page.navigate(baseUrl);
            var card = page.locator(".launch-card", new Page.LocatorOptions().setHasText("Workflow Portal"));
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(card).isVisible();
        } finally {
            page.close();
        }
    }

    @Test
    @Order(22)
    void dashboardShowsYadocPortalBox() {
        var page = browser.newPage();
        try {
            page.navigate(baseUrl);
            var card = page.locator(".launch-card", new Page.LocatorOptions().setHasText("yadoc Portal"));
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(card).isVisible();
        } finally {
            page.close();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Polls /api/containers/{name}/launch/progress until done=true or timeout.
     */
    private String waitUntilDone(String name) throws InterruptedException {
        long deadline = System.currentTimeMillis() + LAUNCH_TIMEOUT_MS;
        String last = "{}";
        while (System.currentTimeMillis() < deadline) {
            var resp = api.get("/api/containers/" + name + "/launch/progress");
            if (resp.status() == 200) {
                last = resp.text();
                System.out.println("[" + name + "] progress: " + summarize(last));
                if (last.contains("\"done\":true")) return last;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        fail("Timed out waiting for container '" + name + "' to finish launching. Last: " + last);
        return last;
    }

    private String extractIp(String progressJson) {
        Matcher m = IP_PATTERN.matcher(progressJson);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Polls an HTTP URL until it returns a response or retries are exhausted.
     */
    private int waitForHttp(String url, int maxRetries) throws InterruptedException {
        for (int i = 0; i < maxRetries; i++) {
            try {
                var ctx = playwright.request().newContext();
                var resp = ctx.get(url);
                int status = resp.status();
                ctx.dispose();
                if (status > 0) return status;
            } catch (Exception ignored) {}
            Thread.sleep(2000);
        }
        return -1;
    }

    private void stopContainer(String name) {
        try {
            api.post("/api/containers/" + name + "/stop");
        } catch (Exception ignored) {}
    }

    private String summarize(String json) {
        // Show phase and last message only (avoid huge log output)
        var phaseM = Pattern.compile("\"phase\":\"([^\"]+)\"").matcher(json);
        var msgM = Pattern.compile("\"messages\":\\[([^\\]]+)\\]").matcher(json);
        String phase = phaseM.find() ? phaseM.group(1) : "?";
        String lastMsg = "";
        if (msgM.find()) {
            var msgs = msgM.group(1).split(",");
            if (msgs.length > 0) lastMsg = msgs[msgs.length - 1].trim().replaceAll("\"", "");
        }
        return "phase=" + phase + " last=" + lastMsg;
    }

    private static String resolveUrl() {
        String prop = System.getProperty("host.portal.url");
        if (prop != null && !prop.isBlank()) return prop;
        String env = System.getenv("HOST_PORTAL_URL");
        if (env != null && !env.isBlank()) return env;
        return DEFAULT_URL;
    }
}
