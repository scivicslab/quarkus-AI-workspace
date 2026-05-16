package com.scivicslab.serviceportal.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Minimal HTTP helper for E2E tests — no RestAssured dependency.
 */
class E2EHttp {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Polls /api/status until the named tool is READY, then returns its actual port.
     * Throws AssertionError if the tool reaches FAILED state or times out.
     */
    static int waitForToolReady(int portalPort, String toolName, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String status = get(portalPort, "/api/status");
            JsonNode root = MAPPER.readTree(status);
            for (JsonNode session : root.path("activeSessions")) {
                if (toolName.equals(session.path("toolName").asText())) {
                    String state = session.path("state").asText();
                    if ("READY".equals(state))
                        return session.path("port").asInt();
                    if ("FAILED".equals(state))
                        throw new AssertionError(toolName + " reached FAILED state");
                }
            }
            Thread.sleep(1_000);
        }
        throw new AssertionError(toolName + " did not reach READY within " + timeoutMs + "ms");
    }

    /** Waits for a tool to disappear from activeSessions (i.e. stopped). */
    static void waitForToolStopped(int portalPort, String toolName, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String status = get(portalPort, "/api/status");
            JsonNode root = MAPPER.readTree(status);
            boolean stillActive = false;
            for (JsonNode session : root.path("activeSessions")) {
                if (toolName.equals(session.path("toolName").asText())) {
                    String state = session.path("state").asText();
                    if ("READY".equals(state) || "STARTING".equals(state)) {
                        stillActive = true;
                        break;
                    }
                }
            }
            if (!stillActive) return;
            Thread.sleep(500);
        }
    }

    static String get(int port, String path) throws Exception {
        return getUrl("http://localhost:" + port + path);
    }

    static String getUrl(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(10_000);
        int status = conn.getResponseCode();
        if (status != 200)
            throw new AssertionError("GET " + urlStr + " returned " + status);
        try (var is = conn.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static String post(int port, String path, Map<String, String> params) throws Exception {
        return postUrl("http://localhost:" + port + path, toJson(params));
    }

    static void assertContains(String body, String expected, String message) {
        if (!body.contains(expected))
            throw new AssertionError(message + " — expected to contain: " + expected + "\nActual: " + body);
    }

    /** POST with a raw JSON string to a full URL (not just port+path). */
    static String postRaw(String url, String json) throws Exception {
        return postBody(url, json, "application/json");
    }

    /** POST with a plain-text body to a full URL. */
    static String postText(String url, String text) throws Exception {
        return postBody(url, text, "text/plain");
    }

    private static String postBody(String urlStr, String body, String contentType) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", contentType);
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(30_000);
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int status = conn.getResponseCode();
        if (status != 200)
            throw new AssertionError("POST " + urlStr + " returned " + status + ": " +
                    new String(conn.getErrorStream() != null ? conn.getErrorStream().readAllBytes() : new byte[0], StandardCharsets.UTF_8));
        try (var is = conn.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String postUrl(String urlStr, String json) throws Exception {
        return postBody(urlStr, json, "application/json");
    }

    /** Extract the first MCP gateway URL from chat-ui /api/config JSON. */
    static String extractGatewayUrl(String configJson) {
        int start = configJson.indexOf("\"agentLoopMcpUrls\":\"");
        if (start < 0) throw new AssertionError("agentLoopMcpUrls not found in config: " + configJson);
        start += "\"agentLoopMcpUrls\":\"".length();
        int end = configJson.indexOf("\"", start);
        return configJson.substring(start, end).split(",")[0].trim();
    }

    /**
     * Polls /api/status until the named tool is READY and its accessUrl is populated,
     * then returns that accessUrl. This is the URL the dashboard link points to —
     * it may differ from a plain http://localhost:{port}/ when a proxy (e.g. K8sPups)
     * overrides it.
     */
    static String waitForToolAccessUrl(int portalPort, String toolName, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String status = get(portalPort, "/api/status");
            JsonNode root = MAPPER.readTree(status);
            for (JsonNode session : root.path("activeSessions")) {
                if (toolName.equals(session.path("toolName").asText())) {
                    String state = session.path("state").asText();
                    if ("READY".equals(state)) {
                        String url = session.path("accessUrl").asText(null);
                        if (url != null && !url.isBlank()) return url;
                    }
                    if ("FAILED".equals(state))
                        throw new AssertionError(toolName + " reached FAILED state");
                }
            }
            Thread.sleep(1_000);
        }
        throw new AssertionError(toolName + " accessUrl not available within " + timeoutMs + "ms");
    }

    /** Waits for the named management service (e.g. "quarkus-mcp-gateway") to reach READY state. */
    static void waitForManagementServiceReady(int portalPort, String serviceName, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String status = get(portalPort, "/api/status");
            JsonNode root = MAPPER.readTree(status);
            for (JsonNode svc : root.path("managementServices")) {
                if (serviceName.equals(svc.path("toolName").asText())) {
                    String state = svc.path("state").asText();
                    if ("READY".equals(state)) return;
                    if ("FAILED".equals(state))
                        throw new AssertionError(serviceName + " reached FAILED state");
                    System.out.println("  " + serviceName + " state: " + state);
                }
            }
            Thread.sleep(2_000);
        }
        throw new AssertionError(serviceName + " did not reach READY within " + timeoutMs + "ms");
    }

    /** Wait until expectedCount instances of toolName are all READY. Returns their ports. */
    static List<Integer> waitForAllToolsReady(int portalPort, String toolName, int expectedCount, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String status = get(portalPort, "/api/status");
            JsonNode root = MAPPER.readTree(status);
            List<Integer> readyPorts = new ArrayList<>();
            for (JsonNode session : root.path("activeSessions")) {
                if (toolName.equals(session.path("toolName").asText())) {
                    String state = session.path("state").asText();
                    if ("FAILED".equals(state)) throw new AssertionError(toolName + " reached FAILED state");
                    if ("READY".equals(state)) readyPorts.add(session.path("port").asInt());
                }
            }
            if (readyPorts.size() >= expectedCount) return readyPorts;
            Thread.sleep(1_000);
        }
        throw new AssertionError(toolName + " did not have " + expectedCount + " READY instances within " + timeoutMs + "ms");
    }

    private static String toJson(Map<String, String> params) {
        var sb = new StringBuilder("{");
        params.forEach((k, v) -> {
            if (sb.length() > 1) sb.append(",");
            sb.append("\"").append(k).append("\":\"").append(v).append("\"");
        });
        sb.append("}");
        return sb.toString();
    }
}
