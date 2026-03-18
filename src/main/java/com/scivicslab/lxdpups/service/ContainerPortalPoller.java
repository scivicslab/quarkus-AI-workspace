package com.scivicslab.lxdpups.service;

import com.scivicslab.lxdpups.config.PortalConfigLoader;
import com.scivicslab.lxdpups.model.ContainerProgressResponse;
import com.scivicslab.lxdpups.model.ServiceProgressSummary;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Polls running LXC containers' portal /api/progress endpoints.
 * Only active in host mode.
 */
@ApplicationScoped
public class ContainerPortalPoller {

    private static final Logger LOG = Logger.getLogger(ContainerPortalPoller.class.getName());

    @Inject
    ContainerManager containerManager;

    @Inject
    PortalConfigLoader configLoader;

    private final ConcurrentHashMap<String, ContainerProgressResponse> cache = new ConcurrentHashMap<>();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Scheduled(every = "5s")
    void poll() {
        if (configLoader.getConfig().isContainerMode()) return;

        var containers = containerManager.list("local");
        // Remove stale entries
        var activeNames = new java.util.HashSet<String>();
        for (var c : containers) {
            activeNames.add(c.name());
        }
        cache.keySet().removeIf(name -> !activeNames.contains(name));

        // Poll each running container
        for (var c : containers) {
            if (!"Running".equals(c.status())) continue;
            if (c.ip() == null || c.ip().isEmpty()) continue;

            var url = "http://" + c.ip() + ":8080/api/progress";
            try {
                var request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(3))
                        .GET()
                        .build();
                var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    var progress = mapper.readValue(response.body(), ContainerProgressResponse.class);
                    cache.put(c.name(), progress);
                }
            } catch (Exception e) {
                // Portal not reachable — show offline status
                cache.put(c.name(), new ContainerProgressResponse(
                        c.name() + " (portal offline)",
                        List.of()
                ));
            }
        }
    }

    /**
     * Get all cached container progress data.
     */
    public Map<String, ContainerProgressResponse> getAllProgress() {
        return Map.copyOf(cache);
    }
}
