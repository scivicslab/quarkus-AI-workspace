package com.scivicslab.serviceportal.e2e;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Playwright E2E UI tests — verifies that the quarkus-chat-ui web UI
 * reflects the correct provider when launched via service-portal.
 *
 * Required environment variable:
 *   TEST_JARS_DIR — directory containing quarkus-chat-ui.jar
 */
@DisplayName("quarkus-chat-ui UI provider display via service-portal")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChatUiProviderUI_IT {

    private static final int PORTAL_PORT     = 18082;
    private static final int CHAT_UI_PORT    = 18100;
    private static final int MOCK_VLLM_PORT  = 19998;
    private static final int POLL_TIMEOUT_MS = 30_000;

    private static ServicePortalProcess portal;
    private static MockVllmServer       mockVllm;

    private static Playwright playwright;
    private static Browser    browser;

    @BeforeAll
    static void startAll() throws Exception {
        URL configUrl = ChatUiProviderUI_IT.class.getClassLoader()
            .getResource("service-portal-test.yaml");
        if (configUrl == null) throw new IllegalStateException("service-portal-test.yaml not found");
        Path configPath = Paths.get(configUrl.toURI());
        Path testJarsDir = prepareTestJarsDir();
        portal = ServicePortalProcess.start(configPath, PORTAL_PORT,
            Map.of("TEST_JARS_DIR", testJarsDir.toString()));
        RestAssured.baseURI = "http://localhost";
        RestAssured.port    = PORTAL_PORT;

        mockVllm = new MockVllmServer(MOCK_VLLM_PORT);
        mockVllm.start();

        playwright = Playwright.create();
        browser = playwright.chromium().launch(
            new BrowserType.LaunchOptions().setHeadless(true)
        );
    }

    @AfterAll
    static void stopAll() {
        if (browser    != null) browser.close();
        if (playwright != null) playwright.close();
        if (portal     != null) portal.stop();
        if (mockVllm   != null) mockVllm.stop();
    }

    // ---------------------------------------------------------------
    // claude
    // ---------------------------------------------------------------

    @Test @Order(1)
    @DisplayName("UI: provider=claude shows single-user chat (no login screen)")
    void claude_uiShowsSingleUserChat() throws Exception {
        launchAndWaitReady("claude", Map.of(
            "provider", "claude",
            "workdir",  System.getProperty("user.home") + "/works"
        ));
        try (Page page = browser.newPage()) {
            page.navigate("http://localhost:" + CHAT_UI_PORT,
                new Page.NavigateOptions().setWaitUntil(WaitUntilState.LOAD));
            // Wait for JS to finish initializing (app div becomes visible in single-user mode)
            page.locator("#app").waitFor(
                new com.microsoft.playwright.Locator.WaitForOptions().setTimeout(10_000));

            assertThat(page.locator("#prompt-input").isVisible())
                .as("chat input must be visible in single-user mode").isTrue();
            assertThat(page.locator("#login-screen").isVisible())
                .as("login screen must be hidden for claude (single-user)").isFalse();
        } finally {
            stopChatUi(CHAT_UI_PORT);
        }
    }

    // ---------------------------------------------------------------
    // codex
    // ---------------------------------------------------------------

    @Test @Order(2)
    @DisplayName("UI: provider=codex shows single-user chat (no login screen)")
    void codex_uiShowsSingleUserChat() throws Exception {
        launchAndWaitReady("codex", Map.of(
            "provider", "codex",
            "workdir",  System.getProperty("user.home") + "/works"
        ));
        try (Page page = browser.newPage()) {
            page.navigate("http://localhost:" + CHAT_UI_PORT,
                new Page.NavigateOptions().setWaitUntil(WaitUntilState.LOAD));
            page.locator("#app").waitFor(
                new com.microsoft.playwright.Locator.WaitForOptions().setTimeout(10_000));

            assertThat(page.locator("#prompt-input").isVisible())
                .as("chat input must be visible in single-user mode").isTrue();
            assertThat(page.locator("#login-screen").isVisible())
                .as("login screen must be hidden for codex (single-user)").isFalse();
        } finally {
            stopChatUi(CHAT_UI_PORT);
        }
    }

    // ---------------------------------------------------------------
    // openai-compat (local LLM, single-user)
    // ---------------------------------------------------------------

    @Test @Order(3)
    @DisplayName("UI: provider=openai-compat without multi-user flag shows single-user chat")
    void openaiCompat_uiShowsSingleUserChat() throws Exception {
        launchAndWaitReady("openai-compat", Map.of(
            "provider", "openai-compat",
            "servers",  "http://localhost:" + MOCK_VLLM_PORT,
            "workdir",  System.getProperty("user.home") + "/works"
        ));
        try (Page page = browser.newPage()) {
            page.navigate("http://localhost:" + CHAT_UI_PORT,
                new Page.NavigateOptions().setWaitUntil(WaitUntilState.LOAD));
            page.locator("#app").waitFor(
                new com.microsoft.playwright.Locator.WaitForOptions().setTimeout(10_000));

            assertThat(page.locator("#prompt-input").isVisible())
                .as("chat input must be visible in single-user mode").isTrue();
            assertThat(page.locator("#login-screen").isVisible())
                .as("login screen must be hidden without multi-user flag").isFalse();
        } finally {
            stopChatUi(CHAT_UI_PORT);
        }
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private static Path prepareTestJarsDir() throws Exception {
        String override = System.getProperty("test.jars.dir");
        if (override != null) return Path.of(override);

        // Use SCIVICSLAB_HOME (deployed JARs) if quarkus-chat-ui.jar is there
        String scivicsHome = System.getenv("SCIVICSLAB_HOME");
        if (scivicsHome == null) scivicsHome = System.getProperty("user.home") + "/works";
        Path deployed = Path.of(scivicsHome, "quarkus-chat-ui.jar");
        if (Files.exists(deployed)) return deployed.getParent();

        // Fall back: auto-discover from source target and copy with non-versioned name
        Path chatUiTarget = findChatUiAppJar();
        Path tempDir = Files.createTempDirectory("service-portal-ui-test-jars-");
        tempDir.toFile().deleteOnExit();
        Files.copy(chatUiTarget, tempDir.resolve("quarkus-chat-ui.jar"));
        return tempDir;
    }

    private static Path findChatUiAppJar() throws Exception {
        String envDir = System.getenv("TEST_JARS_DIR");
        if (envDir != null) {
            Path named = Path.of(envDir, "quarkus-chat-ui.jar");
            if (Files.exists(named)) return named;
            try (var stream = Files.list(Path.of(envDir))) {
                Path found = stream
                    .filter(p -> p.getFileName().toString().startsWith("chat-ui-app")
                              && p.getFileName().toString().endsWith("-runner.jar"))
                    .findFirst().orElse(null);
                if (found != null) return found;
            }
        }
        Path base = Path.of("../../quarkus-chat-ui/app/target").toAbsolutePath();
        try (var stream = Files.list(base)) {
            return stream
                .filter(p -> p.getFileName().toString().startsWith("chat-ui-app")
                          && p.getFileName().toString().endsWith("-runner.jar"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                    "quarkus-chat-ui uber-JAR not found. Build it first: " +
                    "cd quarkus-chat-ui && mvn package -DskipTests"));
        }
    }

    private void launchAndWaitReady(String label, Map<String, String> params) throws Exception {
        given()
            .contentType(ContentType.JSON)
            .body(params)
        .when()
            .post("/api/tool/quarkus-chat-ui/launch")
        .then()
            .statusCode(200);

        String state = pollUntilTerminal(POLL_TIMEOUT_MS);
        if (!"READY".equals(state)) {
            String logs = fetchLogs(CHAT_UI_PORT);
            fail("quarkus-chat-ui [" + label + "] reached [" + state + "] instead of READY.\nLogs:\n" + logs);
        }
    }

    private void stopChatUi(int port) {
        given()
            .contentType(ContentType.JSON)
        .when()
            .post("/api/tool/quarkus-chat-ui/{port}/stop", port)
        .then()
            .statusCode(200);

        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            String state = getToolState(port);
            if ("STOPPED".equals(state) || "NOT_FOUND".equals(state)) return;
        }
    }

    private String pollUntilTerminal(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String state = getToolState(CHAT_UI_PORT);
            if ("READY".equals(state) || "FAILED".equals(state)) return state;
            Thread.sleep(1_000);
        }
        return "TIMEOUT";
    }

    @SuppressWarnings("unchecked")
    private String getToolState(int port) {
        Map<String, Object> status = given()
            .when().get("/api/status")
            .then().statusCode(200)
            .extract().as(Map.class);
        List<Map<String, Object>> sessions =
            (List<Map<String, Object>>) status.get("activeSessions");
        if (sessions == null) return "NOT_FOUND";
        return sessions.stream()
            .filter(s -> "quarkus-chat-ui".equals(s.get("toolName"))
                      && Integer.valueOf(port).equals(s.get("port")))
            .map(s -> String.valueOf(s.get("state")))
            .findFirst()
            .orElse("NOT_FOUND");
    }

    @SuppressWarnings("unchecked")
    private String fetchLogs(int port) {
        try {
            Map<String, Object> resp = given()
                .when().get("/api/tool/quarkus-chat-ui/{port}/logs", port)
                .then().statusCode(200)
                .extract().as(Map.class);
            List<String> logs = (List<String>) resp.get("logs");
            return logs == null ? "(no logs)" : String.join("\n", logs);
        } catch (Exception e) {
            return "(failed to fetch logs: " + e.getMessage() + ")";
        }
    }
}
