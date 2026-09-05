package com.scivicslab.aiworkspace.rest;

import jakarta.enterprise.context.ApplicationScoped;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Asks each running tool what it is doing ({@code ActivitySummary_260905_oo01}).
 *
 * <p>A tool answers {@code GET /api/activity} with one or two lines about the work it is on, and
 * with the parts that work is divided into. What counts as its work, and how to say it, is the
 * tool's to decide — this only asks and collects.</p>
 *
 * <p>Not every tool answers. Some have no such endpoint, and one that is stopping may not answer at
 * all. Each is asked with a short deadline and all are asked at once, so a tool that never replies
 * costs the page that deadline rather than the sum of every tool's.</p>
 */
@ApplicationScoped
public class ActivityProbe {

    private static final Logger LOG = Logger.getLogger(ActivityProbe.class.getName());

    /**
     * How long one tool gets to answer.
     *
     * <p>Short on purpose. This is drawn on a page a human is waiting for, and a tool that cannot
     * say what it is doing within a second is better shown as blank than as a delay.</p>
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    /**
     * What one tool said about itself.
     *
     * @param summary the line for the Instances table, or {@code ""} when it did not answer
     * @param asOf    when the tool produced that answer, or {@code ""} — an answer can be up to
     *                half an hour old, and the screen has to be able to say so
     * @param parts   the breakdown for the Instance Detail screen; empty when the tool has none
     */
    public record Activity(String summary, String asOf, List<Part> parts) {
        /** One part of a tool's work: a project, a document, a conversation. */
        public record Part(String name, String summary) {}

        /** The answer for a tool that did not answer. */
        public static Activity none() {
            return new Activity("", "", List.of());
        }
    }

    /**
     * Asks every given tool at once and collects what comes back within the deadline.
     *
     * @param urls the base URL of each tool, keyed by whatever the caller wants to look them up by
     * @return one entry per key; a tool that did not answer maps to {@link Activity#none()}
     */
    public Map<String, Activity> askAll(Map<String, String> urls) {
        Map<String, CompletableFuture<Activity>> inFlight = new LinkedHashMap<>();
        urls.forEach((key, base) -> inFlight.put(key, ask(base)));

        Map<String, Activity> out = new LinkedHashMap<>();
        inFlight.forEach((key, future) -> {
            try {
                out.put(key, future.get(TIMEOUT.toMillis() + 500, java.util.concurrent.TimeUnit.MILLISECONDS));
            } catch (Exception e) {
                out.put(key, Activity.none());
            }
        });
        return out;
    }

    /** Asks one tool, answering {@link Activity#none()} for anything that is not a clean reply. */
    private CompletableFuture<Activity> ask(String baseUrl) {
        String url = baseUrl.endsWith("/") ? baseUrl + "api/activity" : baseUrl + "/api/activity";
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url)).GET().timeout(TIMEOUT).build();
        } catch (Exception e) {
            return CompletableFuture.completedFuture(Activity.none());
        }
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(r -> r.statusCode() == 200 ? parse(r.body()) : Activity.none())
                .exceptionally(e -> Activity.none());
    }

    /**
     * Reads a tool's answer.
     *
     * <p>Hand-written rather than bound to a class: this reads three fields of one small object
     * from a tool that may be any version of itself, and anything unreadable is the same answer as
     * no answer.</p>
     */
    static Activity parse(String body) {
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            String summary = root.path("summary").asText("");
            String asOf = root.path("asOf").asText("");
            List<Activity.Part> parts = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode p : root.path("parts")) {
                String name = p.path("name").asText("");
                String text = p.path("summary").asText("");
                if (!text.isBlank()) parts.add(new Activity.Part(name, text));
            }
            return new Activity(summary, asOf, List.copyOf(parts));
        } catch (Exception e) {
            LOG.fine("Unreadable activity answer: " + e.getMessage());
            return Activity.none();
        }
    }
}
