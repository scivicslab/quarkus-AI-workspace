package com.scivicslab.serviceportal.e2e;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.fail;

/**
 * Integration tests: verifies that each tool in service-portal-test.yaml
 * starts successfully via the REST API and reaches READY state.
 *
 * Required environment variable:
 *   TEST_JARS_DIR  — directory containing the downloaded uber-JARs
 *                    (e.g. /home/devteam/works/test-jars)
 *
 * All three tools share one service-portal instance to avoid port conflicts.
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
        if (configUrl == null) {
            throw new IllegalStateException("service-portal-test.yaml not found in test resources");
        }
        Path configPath = Paths.get(configUrl.toURI());
        portal = ServicePortalProcess.start(configPath, PORTAL_PORT);
        RestAssured.baseURI = "http://localhost";
        RestAssured.port    = PORTAL_PORT;
    }

    @AfterAll
    static void stopPortal() {
        if (portal != null) portal.stop();
    }

    // ---------------------------------------------------------------
    // Tests — one per tool
    // ---------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("html-saurus starts and reaches READY")
    void htmlSaurus_startsSuccessfully() throws Exception {
        assertToolReady("html-saurus", 18110, Map.of(
            "dir", System.getProperty("user.home") + "/works"
        ));
    }

    @Test
    @Order(2)
    @DisplayName("quarkus-chat-ui starts and reaches READY")
    void chatUi_startsSuccessfully() throws Exception {
        assertToolReady("quarkus-chat-ui", 18100, Map.of(
            "workdir",  System.getProperty("user.home") + "/works",
            "provider", "claude"
        ));
    }

    @Test
    @Order(3)
    @DisplayName("turing-workflow-editor starts and reaches READY")
    void turingWorkflowEditor_startsSuccessfully() throws Exception {
        assertToolReady("turing-workflow-editor", 18120, Map.of(
            "workdir", System.getProperty("user.home") + "/works"
        ));
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private void assertToolReady(String toolName, int toolPort,
                                 Map<String, String> params) throws Exception {
        given()
            .contentType(ContentType.JSON)
            .body(params)
        .when()
            .post("/api/tool/{name}/launch", toolName)
        .then()
            .statusCode(200);

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
}
