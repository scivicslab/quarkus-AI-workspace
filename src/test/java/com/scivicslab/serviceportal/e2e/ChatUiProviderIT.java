package com.scivicslab.serviceportal.e2e;

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
 * Integration tests — verifies that quarkus-chat-ui starts with the correct
 * provider and single-user mode when launched via service-portal.
 *
 * Each provider is tested in its own nested class so that chat-ui is stopped
 * and restarted cleanly between providers.
 *
 * Required environment variable:
 *   TEST_JARS_DIR — directory containing quarkus-chat-ui.jar
 */
@DisplayName("quarkus-chat-ui provider selection via service-portal")
class ChatUiProviderIT {

    private static final int PORTAL_PORT     = 18081;
    private static final int CHAT_UI_PORT    = 18100;
    private static final int MOCK_VLLM_PORT  = 19999;
    private static final int POLL_TIMEOUT_MS = 30_000;

    private static ServicePortalProcess portal;
    private static MockVllmServer       mockVllm;

    @BeforeAll
    static void startPortal() throws Exception {
        URL configUrl = ChatUiProviderIT.class.getClassLoader()
            .getResource("service-portal-test.yaml");
        if (configUrl == null) throw new IllegalStateException("service-portal-test.yaml not found");
        Path configPath = Paths.get(configUrl.toURI());

        // Locate the chat-ui uber-JAR and copy it with the non-versioned name expected by the YAML
        Path testJarsDir = prepareTestJarsDir();

        portal = ServicePortalProcess.start(configPath, PORTAL_PORT,
            Map.of("TEST_JARS_DIR", testJarsDir.toString()));
        RestAssured.baseURI = "http://localhost";
        RestAssured.port    = PORTAL_PORT;

        mockVllm = new MockVllmServer(MOCK_VLLM_PORT);
        mockVllm.start();
    }

    /**
     * Finds the quarkus-chat-ui uber-JAR in the project tree and copies it into a
     * temporary directory as "quarkus-chat-ui.jar" — the non-versioned name used by
     * the test YAML.
     */
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
        Path tempDir = Files.createTempDirectory("service-portal-test-jars-");
        tempDir.toFile().deleteOnExit();
        Files.copy(chatUiTarget, tempDir.resolve("quarkus-chat-ui.jar"));
        return tempDir;
    }

    private static Path findChatUiAppJar() throws Exception {
        // Check env var first
        String envDir = System.getenv("TEST_JARS_DIR");
        if (envDir != null) {
            Path named = Path.of(envDir, "quarkus-chat-ui.jar");
            if (Files.exists(named)) return named;
            // Try versioned name pattern
            try (var stream = Files.list(Path.of(envDir))) {
                Path found = stream
                    .filter(p -> p.getFileName().toString().startsWith("chat-ui-app")
                              && p.getFileName().toString().endsWith("-runner.jar"))
                    .findFirst().orElse(null);
                if (found != null) return found;
            }
        }
        // Fallback: search relative to cwd
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

    @AfterAll
    static void stopAll() {
        if (portal  != null) portal.stop();
        if (mockVllm != null) mockVllm.stop();
    }

    // ---------------------------------------------------------------
    // claude
    // ---------------------------------------------------------------

    @Test @Order(1)
    @DisplayName("provider=claude → providerId=claude, multiUser=false")
    void claude_correctProviderAndSingleUser() throws Exception {
        launchAndWaitReady("claude", Map.of(
            "provider", "claude",
            "workdir",  System.getProperty("user.home") + "/works"
        ));
        try {
            Map<String, Object> config = fetchChatUiConfig(CHAT_UI_PORT);
            assertThat(config.get("providerId"))
                .as("providerId for claude launch").isEqualTo("claude");
            assertThat(config.get("multiUser"))
                .as("multiUser must be false for claude").isEqualTo(false);
        } finally {
            stopChatUi(CHAT_UI_PORT);
        }
    }

    // ---------------------------------------------------------------
    // codex
    // ---------------------------------------------------------------

    @Test @Order(2)
    @DisplayName("provider=codex → providerId=codex, multiUser=false")
    void codex_correctProviderAndSingleUser() throws Exception {
        launchAndWaitReady("codex", Map.of(
            "provider", "codex",
            "workdir",  System.getProperty("user.home") + "/works"
        ));
        try {
            Map<String, Object> config = fetchChatUiConfig(CHAT_UI_PORT);
            assertThat(config.get("providerId"))
                .as("providerId for codex launch").isEqualTo("codex");
            assertThat(config.get("multiUser"))
                .as("multiUser must be false for codex").isEqualTo(false);
        } finally {
            stopChatUi(CHAT_UI_PORT);
        }
    }

    // ---------------------------------------------------------------
    // openai-compat (local LLM) — single-user (no chat-ui.multi-user flag)
    // ---------------------------------------------------------------

    @Test @Order(3)
    @DisplayName("provider=openai-compat, no multi-user flag → providerId=openai-compat, multiUser=false")
    void openaiCompat_singleUserByDefault() throws Exception {
        launchAndWaitReady("openai-compat-single", Map.of(
            "provider", "openai-compat",
            "servers",  "http://localhost:" + MOCK_VLLM_PORT,
            "workdir",  System.getProperty("user.home") + "/works"
        ));
        try {
            Map<String, Object> config = fetchChatUiConfig(CHAT_UI_PORT);
            assertThat(config.get("providerId"))
                .as("providerId for openai-compat launch").isEqualTo("openai-compat");
            assertThat(config.get("multiUser"))
                .as("multiUser must be false without chat-ui.multi-user=true").isEqualTo(false);
        } finally {
            stopChatUi(CHAT_UI_PORT);
        }
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

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

        // Wait until STOPPED so the next test starts clean
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
    private Map<String, Object> fetchChatUiConfig(int port) {
        return given()
            .baseUri("http://localhost").port(port)
        .when()
            .get("/api/config")
        .then()
            .statusCode(200)
            .extract().as(Map.class);
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
