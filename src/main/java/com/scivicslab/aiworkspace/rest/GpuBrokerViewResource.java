package com.scivicslab.aiworkspace.rest;

import com.scivicslab.aiworkspace.broker.BrokerQueues;
import com.scivicslab.aiworkspace.config.GpuBrokerUrl;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Catalog's GPU broker tile reads the broker through here. The broker runs once, inside the W206
 * cluster, and this portal never starts one; this only reads its {@code GET /queues}. The page cannot
 * read the broker itself: the broker is another origin and does not allow the portal's page to read
 * its answers.
 */
@Path("/api/gpu-broker")
public class GpuBrokerViewResource {

    // Created on first use, not in a static initializer: the native image initializes this resource
    // class at build time, and an HttpClient made there is frozen into the image heap, which the
    // native build refuses (HttpClientFacade). ProxyResource does the same.
    private static volatile HttpClient client;

    private static HttpClient client() {
        HttpClient c = client;
        if (c == null) {
            c = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();
            client = c;
        }
        return c;
    }

    /**
     * @return {@code {"configured":false}} when no broker URL is set; otherwise {@code url},
     *         {@code reachable} and, when reachable, {@code queues} ({@link BrokerQueues.Queue}) or,
     *         when not, {@code error}
     */
    @GET
    @Path("/queues")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> queues() {
        Map<String, Object> body = new LinkedHashMap<>();
        var url = GpuBrokerUrl.resolve();
        body.put("configured", url.isPresent());
        if (url.isEmpty()) return body;
        body.put("url", url.get());
        try {
            HttpResponse<String> r = client().send(
                    HttpRequest.newBuilder(URI.create(url.get() + "/queues")).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                body.put("reachable", false);
                body.put("error", "the broker answered HTTP " + r.statusCode());
                return body;
            }
            body.put("reachable", true);
            body.put("queues", BrokerQueues.parse(r.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            body.put("reachable", false);
            body.put("error", "interrupted");
        } catch (Exception e) {
            body.put("reachable", false);
            body.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        return body;
    }
}
