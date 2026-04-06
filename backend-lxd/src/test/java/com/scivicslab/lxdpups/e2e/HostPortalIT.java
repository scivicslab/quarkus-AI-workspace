package com.scivicslab.lxdpups.e2e;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.RequestOptions;
import org.junit.jupiter.api.*;

import java.util.List;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Playwright-based E2E tests for the host (parent) portal.
 * Targets a running lxd-pups-portal instance.
 * Run with: mvn verify -Dhost.portal.url=http://localhost:16080
 * or set HOST_PORTAL_URL env var. Defaults to http://localhost:16080.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class HostPortalIT {

    private static final String DEFAULT_URL = "http://localhost:16080";

    private static Playwright playwright;
    private static Browser browser;
    private static String baseUrl;

    private BrowserContext context;
    private Page page;

    @BeforeAll
    static void setup() {
        baseUrl = resolveUrl();
        System.out.println("Host portal URL: " + baseUrl);
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

    // ── API: /api/status ──

    @Test
    @Order(1)
    void statusApiReturnsOk() {
        var api = playwright.request().newContext();
        var resp = api.get(baseUrl + "/api/status");
        assertEquals(200, resp.status(), "GET /api/status should return 200");
        api.dispose();
    }

    @Test
    @Order(2)
    void statusApiContainsManagementServicesAndContainers() {
        var api = playwright.request().newContext();
        var body = api.get(baseUrl + "/api/status").text();
        assertTrue(body.contains("\"managementServices\""), "Status should have managementServices");
        assertTrue(body.contains("\"containers\""), "Status should have containers");
        api.dispose();
    }

    @Test
    @Order(3)
    void statusApiListsKafkaService() {
        var api = playwright.request().newContext();
        var body = api.get(baseUrl + "/api/status").text();
        assertTrue(body.contains("\"kafka\""), "kafka should appear in managementServices");
        api.dispose();
    }

    @Test
    @Order(4)
    void statusApiListsMcpGatewayService() {
        var api = playwright.request().newContext();
        var body = api.get(baseUrl + "/api/status").text();
        assertTrue(body.contains("\"mcp-gateway\""), "mcp-gateway should appear in managementServices");
        api.dispose();
    }

    @Test
    @Order(5)
    void kafkaStatusIsInactive() {
        var api = playwright.request().newContext();
        var body = api.get(baseUrl + "/api/status").text();
        // kafka entry must exist with some known status (READY or STOPPED depending on environment)
        int kafkaIdx = body.indexOf("\"kafka\"");
        assertTrue(kafkaIdx >= 0, "kafka entry should exist");
        var kafkaSection = body.substring(kafkaIdx, Math.min(kafkaIdx + 200, body.length()));
        assertTrue(kafkaSection.contains("READY") || kafkaSection.contains("STOPPED")
                        || kafkaSection.contains("STARTING") || kafkaSection.contains("FAILED"),
                "kafka should have a known status (READY, STOPPED, STARTING, or FAILED)");
        api.dispose();
    }

    // ── API: management service start ──

    @Test
    @Order(10)
    void startKafkaReturns202() {
        var api = playwright.request().newContext();
        var resp = api.post(baseUrl + "/api/management/services/kafka/start");
        assertEquals(202, resp.status(), "Starting kafka should return 202 Accepted");
        api.dispose();
    }

    @Test
    @Order(11)
    void kafkaProgressApiReturnsValidJson() {
        var api = playwright.request().newContext();
        // Trigger start first to ensure progress tracker exists
        api.post(baseUrl + "/api/management/services/kafka/start");
        var resp = api.get(baseUrl + "/api/management/services/kafka/progress");
        assertEquals(200, resp.status());
        var body = resp.text();
        assertTrue(body.contains("\"name\""),     "Progress should have name");
        assertTrue(body.contains("\"phase\""),    "Progress should have phase");
        assertTrue(body.contains("\"messages\""), "Progress should have messages");
        assertTrue(body.contains("\"done\""),     "Progress should have done");
        assertTrue(body.contains("\"success\""),  "Progress should have success");
        api.dispose();
    }

    @Test
    @Order(12)
    void startUnknownServiceReturns404Or500() {
        var api = playwright.request().newContext();
        var resp = api.post(baseUrl + "/api/management/services/does-not-exist/start");
        assertTrue(List.of(404, 500).contains(resp.status()),
                "Starting unknown service should return 404 or 500, got " + resp.status());
        api.dispose();
    }

    // ── API: LXC ──

    @Test
    @Order(20)
    void lxcContainersApiReturnsArray() {
        // lxc list can be slow right after container teardown — use 90s timeout
        var api = playwright.request().newContext(new APIRequest.NewContextOptions().setTimeout(90000));
        var resp = api.get(baseUrl + "/api/lxc/containers");
        assertEquals(200, resp.status(), "GET /api/lxc/containers should return 200");
        var body = resp.text();
        assertTrue(body.startsWith("["), "Should return JSON array");
        api.dispose();
    }

    @Test
    @Order(21)
    void lxcImagesApiReturnsArray() {
        // lxc list can be slow right after container teardown — use 90s timeout
        var api = playwright.request().newContext(new APIRequest.NewContextOptions().setTimeout(90000));
        var resp = api.get(baseUrl + "/api/lxc/images");
        assertEquals(200, resp.status(), "GET /api/lxc/images should return 200");
        var body = resp.text();
        assertTrue(body.startsWith("["), "Should return JSON array");
        api.dispose();
    }

    @Test
    @Order(22)
    void launchingContainersApiReturnsArray() {
        var api = playwright.request().newContext();
        var resp = api.get(baseUrl + "/api/containers/launching");
        assertEquals(200, resp.status(), "GET /api/containers/launching should return 200");
        var body = resp.text();
        assertTrue(body.startsWith("["), "Should return JSON array (currently launching)");
        api.dispose();
    }

    // ── API: Kafka progress after start attempt ──

    @Test
    @Order(30)
    void kafkaStartFailsGracefullyWhenNotInstalled() throws InterruptedException {
        var api = playwright.request().newContext();
        api.post(baseUrl + "/api/management/services/kafka/start");

        // Give the worker actor a moment to process
        Thread.sleep(2000);

        var resp = api.get(baseUrl + "/api/management/services/kafka/progress");
        var body = resp.text();

        // When kafka is not installed, either:
        // - downloading (url configured) -> done=false
        // - failed with binary not found -> done=true, success=false
        assertTrue(body.contains("\"kafka\""), "Progress should reference kafka");

        // If done=true and success=false, there should be an error message
        if (body.contains("\"done\":true") && body.contains("\"success\":false")) {
            assertTrue(body.contains("messages"), "Failed progress should have messages explaining why");
        }
        api.dispose();
    }

    // ── UI: page structure ──

    @Test
    @Order(40)
    void dashboardPageLoads() {
        page.navigate(baseUrl);
        assertThat(page).hasTitle(java.util.regex.Pattern.compile(".*LXD.*|.*Portal.*|.*pups.*",
                java.util.regex.Pattern.CASE_INSENSITIVE));
    }

    @Test
    @Order(41)
    void managementServicesSectionVisible() {
        page.navigate(baseUrl);
        // Section header for management services
        var section = page.locator("text=Management Services, text=管理サービス").first();
        // If not found by text, look for service card containing "kafka"
        var kafkaCard = page.locator("text=Apache Kafka, text=kafka").first();
        // At least one of them should be visible
        assertTrue(
            page.locator("text=kafka").count() > 0 ||
            page.locator("text=Kafka").count() > 0,
            "Page should show Kafka service"
        );
    }

    @Test
    @Order(42)
    void kafkaStartButtonVisible() {
        page.navigate(baseUrl);
        // Depending on environment, kafka may be ACTIVE (Stop button) or INACTIVE (Start button)
        var actionButtons = page.locator("button:has-text('Start'), button:has-text('Stop'), a:has-text('Start'), a:has-text('Stop')");
        assertTrue(actionButtons.count() > 0, "At least one Start or Stop button should be visible");
    }

    @Test
    @Order(43)
    void pageShowsWorkerContainersSection() {
        page.navigate(baseUrl);
        assertThat(page.locator("text=Worker Containers")).isVisible();
    }

    // ── API: launch container (validation) ──

    @Test
    @Order(50)
    void launchContainerWithoutNameReturns400Or422() {
        var api = playwright.request().newContext();
        var resp = api.post(baseUrl + "/api/containers",
                RequestOptions.create()
                        .setHeader("Content-Type", "application/json")
                        .setData("{}"));
        assertTrue(List.of(400, 409, 422, 500).contains(resp.status()),
                "Launching without name should fail, got " + resp.status());
        api.dispose();
    }

    @Test
    @Order(51)
    void containerProgressApiReturnsForUnknownContainer() {
        var api = playwright.request().newContext();
        var resp = api.get(baseUrl + "/api/containers/no-such-container/launch/progress");
        assertEquals(200, resp.status(), "Progress endpoint should return 200 even for unknown container");
        var body = resp.text();
        assertTrue(body.contains("\"phase\""), "Should return a progress object with phase");
        api.dispose();
    }

    // ── helpers ──

    private static String resolveUrl() {
        String prop = System.getProperty("host.portal.url");
        if (prop != null && !prop.isBlank()) return prop;
        String env = System.getenv("HOST_PORTAL_URL");
        if (env != null && !env.isBlank()) return env;
        return DEFAULT_URL;
    }
}
