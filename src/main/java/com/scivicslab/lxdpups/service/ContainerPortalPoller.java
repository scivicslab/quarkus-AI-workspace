package com.scivicslab.lxdpups.service;

import com.scivicslab.lxdpups.config.PortalConfigLoader;
import com.scivicslab.lxdpups.model.ContainerProgressResponse;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.InetSocketAddress;
import java.net.Socket;
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
 * Polls running LXC containers to determine service availability.
 *
 * Health-check strategy depends on image type (see docs/container-lifecycle.md):
 *   - lxd-pups/ai-tools:  GET http://{ip}:16080/api/progress  (child portal)
 *   - lxd-pups/jupyter:   TCP connect to {ip}:16900            (Jupyter Lab)
 *   - lxd-pups/guacamole: TCP connect to {ip}:16901            (Guacamole Tomcat)
 *
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

            var image = c.image();
            if ("lxd-pups/jupyter".equals(image)) {
                pollTcp(c.name(), c.ip(), 16900, "Jupyter");
            } else if ("lxd-pups/claude".equals(image)) {
                pollTcp(c.name(), c.ip(), 16120, "Claude");
            } else if ("lxd-pups/guacamole".equals(image)) {
                pollTcp(c.name(), c.ip(), 16901, "Desktop");
            } else {
                pollPortalApi(c.name(), c.ip());
            }
        }
    }

    /**
     * TCP health check for single-service containers (jupyter, guacamole).
     */
    private void pollTcp(String name, String ip, int port, String label) {
        boolean reachable = false;
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, port), 3000);
            reachable = true;
        } catch (Exception e) {
            // not reachable
        }
        cache.put(name, new ContainerProgressResponse(name, List.of(), reachable));
    }

    /**
     * HTTP health check for ai-tools containers (child portal with service list).
     */
    private void pollPortalApi(String name, String ip) {
        var url = "http://" + ip + ":16080/api/progress";
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                var progress = mapper.readValue(response.body(), ContainerProgressResponse.class);
                cache.put(name, new ContainerProgressResponse(
                        progress.title(), progress.services(), true));
            } else {
                cache.put(name, new ContainerProgressResponse(name, List.of(), false));
            }
        } catch (Exception e) {
            cache.put(name, new ContainerProgressResponse(name, List.of(), false));
        }
    }

    /**
     * Get all cached container progress data.
     */
    public Map<String, ContainerProgressResponse> getAllProgress() {
        return Map.copyOf(cache);
    }
}
