package com.scivicslab.lxdpups.rest;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the REST API.
 * Runs against a real Quarkus instance started by maven-failsafe-plugin (mvn verify).
 * The portal is started on port 16080 during pre-integration-test phase.
 */
class ApiResourceIT {

    private static final String BASE_URL = "http://localhost:16080";
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void statusEndpointReturns200() throws Exception {
        var response = get("/api/status");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("managementServices"));
        assertTrue(response.body().contains("containers"));
    }

    @Test
    void listContainersReturns200() throws Exception {
        var response = get("/api/lxc/containers?remote=local");
        assertEquals(200, response.statusCode());
    }

    @Test
    void listImagesReturns200() throws Exception {
        var response = get("/api/lxc/images");
        assertEquals(200, response.statusCode());
    }

    @Test
    void launchContainerRequiresName() throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/containers"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("name is required"));
    }

    @Test
    void launchContainerRejectsBlankName() throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/containers"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\": \"\"}"))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("name is required"));
    }

    @Test
    void getLaunchProgressForUnknownContainer() throws Exception {
        var response = get("/api/containers/nonexistent/launch/progress");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("nonexistent"));
        assertTrue(response.body().contains("idle"));
    }

    @Test
    void getActiveLaunchesReturnsEmptyByDefault() throws Exception {
        var response = get("/api/containers/launching");
        assertEquals(200, response.statusCode());
        assertEquals("[]", response.body().strip());
    }

    @Test
    void managementServiceProgressForUnknown() throws Exception {
        var response = get("/api/management/services/nonexistent/progress");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("nonexistent"));
    }

    @Test
    void dashboardPageReturnsHtml() throws Exception {
        var response = get("/");
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("content-type")
                .orElse("").contains("text/html"));
    }

    @Test
    void lxcManagerPageReturnsHtml() throws Exception {
        var response = get("/lxc-manager");
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("content-type")
                .orElse("").contains("text/html"));
    }

    @Test
    void containerRecordsReturnsArray() throws Exception {
        var response = get("/api/containers/records");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().startsWith("["));
    }

    @Test
    void containerRecordForUnknownReturns404() throws Exception {
        var response = get("/api/containers/nonexistent/record");
        assertEquals(404, response.statusCode());
    }

    @Test
    void recordActivityForUnknownContainerReturns200() throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/containers/nonexistent/activity"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
    }

    private HttpResponse<String> get(String path) throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
