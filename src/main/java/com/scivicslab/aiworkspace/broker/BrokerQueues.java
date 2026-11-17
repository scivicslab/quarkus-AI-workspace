package com.scivicslab.aiworkspace.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * What the Catalog's GPU broker tile shows of one answer of the broker's {@code GET /queues}: per
 * queue, its endpoints and whether each is UP, its slots in use and in total, and the jobs waiting.
 *
 * <p>A plain function of the broker's JSON, so it is tested without a broker. Fields the broker does
 * not send are shown as zero or as an empty list rather than failing the whole tile.</p>
 */
public final class BrokerQueues {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One endpoint of a queue: its address and the health the broker reports (UP, DOWN, ...). */
    @io.quarkus.runtime.annotations.RegisterForReflection
    public record Endpoint(String address, String health) {
        public boolean up() { return "UP".equals(health); }
    }

    /** One queue as the tile shows it. */
    @io.quarkus.runtime.annotations.RegisterForReflection
    public record Queue(String name, int activeSlots, int totalSlots, int pendingJobs, List<Endpoint> endpoints) {
        public long endpointsUp() { return endpoints.stream().filter(Endpoint::up).count(); }
    }

    private BrokerQueues() {
    }

    /**
     * @param json the body of the broker's {@code GET /queues}, a JSON array of queues
     * @return one {@link Queue} per element, in the broker's order
     * @throws IllegalArgumentException when the body is not a JSON array
     */
    public static List<Queue> parse(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("the broker's /queues is not JSON: " + e.getMessage(), e);
        }
        if (root == null || !root.isArray()) {
            throw new IllegalArgumentException("the broker's /queues is not a JSON array");
        }
        List<Queue> queues = new ArrayList<>();
        for (JsonNode q : root) {
            List<Endpoint> endpoints = new ArrayList<>();
            for (JsonNode e : q.path("endpoints")) {
                endpoints.add(new Endpoint(e.path("address").asText(""), e.path("health").asText("UNKNOWN")));
            }
            queues.add(new Queue(q.path("name").asText(""), q.path("activeSlots").asInt(0),
                    q.path("totalSlots").asInt(0), q.path("pendingJobs").asInt(0), List.copyOf(endpoints)));
        }
        return List.copyOf(queues);
    }
}
