package com.scivicslab.serviceportal.e2e;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.*;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Integration tests — verifies that each tool starts successfully via the
 * service-portal API and actually serves HTTP on its port.
 *
 * Required environment variable:
 *   TEST_JARS_DIR  — directory containing the downloaded uber-JARs
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ToolStartupIT {

    private static final int PORTAL_PORT      = 18080;
    private static final int POLL_TIMEOUT_MS  = 30_000;
    private static final int POLL_INTERVAL_MS = 1_000;

    private static ServicePortalProcess portal;

    @BeforeAll
    static void startPortal() throws Exception {
        URL configUrl = ToolStartupIT.class.getClassLoader()
            .getResource("service-portal-test.yaml");
        if (configUrl == null) throw new IllegalStateException("service-portal-test.yaml not found");
        Path configPath = Paths.get(configUrl.toURI());
        portal = ServicePortalProcess.start(configPath, PORTAL_PORT);
        RestAssured.baseURI = "http://localhost";
        RestAssured.port    = PORTAL_PORT;
    }

    @AfterAll
    static void stopPortal() {
        if (portal != null) portal.stop();
    }

    // ═══════════════════════════════════════════════════════════════
    // html-saurus
    // ═══════════════════════════════════════════════════════════════

    @Test @Order(1)
    @DisplayName("html-saurus: launches and reaches READY")
    void htmlSaurus_launches() throws Exception {
        launchTool("html-saurus", Map.of(
            "dir", System.getProperty("user.home") + "/works"
        ));
        assertReady("html-saurus", 18110);
    }

    @Test @Order(2)
    @DisplayName("html-saurus: HTTP 200 on root")
    void htmlSaurus_rootReturns200() {
        Response r = toolRequest(18110).get("/");
        assertThat(r.statusCode())
            .as("html-saurus root HTTP status").isEqualTo(200);
    }

    @Test @Order(3)
    @DisplayName("html-saurus: portal page lists doc projects")
    void htmlSaurus_portalListsProjects() {
        String body = toolRequest(18110).get("/").asString();
        assertThat(body)
            .as("html-saurus portal page should contain project links")
            .contains("doc_");
    }

    // ═══════════════════════════════════════════════════════════════
    // quarkus-chat-ui
    // ═══════════════════════════════════════════════════════════════

    @Test @Order(4)
    @DisplayName("quarkus-chat-ui: launches and reaches READY")
    void chatUi_launches() throws Exception {
        launchTool("quarkus-chat-ui", Map.of(
            "workdir",  System.getProperty("user.home") + "/works",
            "provider", "claude"
        ));
        assertReady("quarkus-chat-ui", 18100);
    }

    @Test @Order(5)
    @DisplayName("quarkus-chat-ui: HTTP 200 on root")
    void chatUi_rootReturns200() {
        Response r = toolRequest(18100).get("/");
        assertThat(r.statusCode())
            .as("quarkus-chat-ui root HTTP status").isEqualTo(200);
    }

    @Test @Order(6)
    @DisplayName("quarkus-chat-ui: UI page contains expected content")
    void chatUi_uiPageLoads() {
        String body = toolRequest(18100).get("/").asString();
        assertThat(body)
            .as("chat-ui page should contain chat UI HTML")
            .containsAnyOf("chat", "Chat", "message", "provider");
    }

    // ═══════════════════════════════════════════════════════════════
    // turing-workflow-editor
    // ═══════════════════════════════════════════════════════════════

    @Test @Order(7)
    @DisplayName("turing-workflow-editor: launches and reaches READY")
    void turingWorkflowEditor_launches() throws Exception {
        launchTool("turing-workflow-editor", Map.of(
            "workdir", System.getProperty("user.home") + "/works"
        ));
        assertReady("turing-workflow-editor", 18120);
    }

    @Test @Order(8)
    @DisplayName("turing-workflow-editor: HTTP 200 on root")
    void turingWorkflowEditor_rootReturns200() {
        Response r = toolRequest(18120).get("/");
        assertThat(r.statusCode())
            .as("turing-workflow-editor root HTTP status").isEqualTo(200);
    }

    @Test @Order(9)
    @DisplayName("turing-workflow-editor: UI page contains expected content")
    void turingWorkflowEditor_uiPageLoads() {
        String body = toolRequest(18120).get("/").asString();
        assertThat(body)
            .as("turing-workflow-editor page should contain editor UI HTML")
            .containsAnyOf("workflow", "Workflow", "editor", "Editor");
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════

    private void launchTool(String toolName, Map<String, String> params) {
        given()
            .contentType(ContentType.JSON)
            .body(params)
        .when()
            .post("/api/tool/{name}/launch", toolName)
        .then()
            .statusCode(200);
    }

    private void assertReady(String toolName, int toolPort) throws InterruptedException {
        String state = pollUntilTerminal(toolName, toolPort, POLL_TIMEOUT_MS);
        if (!"READY".equals(state)) {
            String logs = fetchLogs(toolName, toolPort);
            fail(toolName + " reached [" + state + "] instead of READY.\nLogs:\n" + logs);
        }
    }

    private String pollUntilTerminal(String toolName, int toolPort,
                                     long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String state = getToolState(toolName, toolPort);
            if ("READY".equals(state) || "FAILED".equals(state) || "STOPPED".equals(state)) {
                return state;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        return "TIMEOUT";
    }

    @SuppressWarnings("unchecked")
    private String getToolState(String toolName, int toolPort) {
        Map<String, Object> status = given()
            .when().get("/api/status")
            .then().statusCode(200)
            .extract().as(Map.class);
        List<Map<String, Object>> sessions =
            (List<Map<String, Object>>) status.get("activeSessions");
        if (sessions == null) return "UNKNOWN";
        return sessions.stream()
            .filter(s -> toolName.equals(s.get("toolName"))
                      && Integer.valueOf(toolPort).equals(s.get("port")))
            .map(s -> String.valueOf(s.get("state")))
            .findFirst()
            .orElse("NOT_FOUND");
    }

    @SuppressWarnings("unchecked")
    private String fetchLogs(String toolName, int toolPort) {
        try {
            Map<String, Object> resp = given()
                .when().get("/api/tool/{name}/{port}/logs", toolName, toolPort)
                .then().statusCode(200)
                .extract().as(Map.class);
            List<String> logs = (List<String>) resp.get("logs");
            return logs == null ? "(no logs)" : String.join("\n", logs);
        } catch (Exception e) {
            return "(failed to fetch logs: " + e.getMessage() + ")";
        }
    }

    /** Direct HTTP request to a tool (bypasses service-portal). */
    private RequestSpecification toolRequest(int toolPort) {
        return given().baseUri("http://localhost").port(toolPort);
    }
}
