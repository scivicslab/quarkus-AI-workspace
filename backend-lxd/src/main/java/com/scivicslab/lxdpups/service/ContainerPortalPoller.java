package com.scivicslab.lxdpups.service;

import com.scivicslab.lxdpups.model.ContainerProgressResponse;
import com.scivicslab.lxdpups.model.HealthCheckConfig;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Polls running LXC containers to determine service availability.
 *
 * Health check strategy uses HealthCheckConfig per image type.
 * All images currently use TCP connect to the main service port.
 */
@ApplicationScoped
public class ContainerPortalPoller {

    private static final Logger LOG = Logger.getLogger(ContainerPortalPoller.class.getName());

    @Inject
    ContainerManager containerManager;

    private final ConcurrentHashMap<String, ContainerProgressResponse> cache = new ConcurrentHashMap<>();

    @Scheduled(every = "5s")
    void poll() {
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

            HealthCheckConfig config = HealthCheckConfig.forImage(c.image());
            boolean reachable = tcpHealthCheck(c.ip(), config.port());
            cache.put(c.name(), new ContainerProgressResponse(c.name(), List.of(), reachable));
        }
    }

    /**
     * TCP health check for container service ports.
     */
    private boolean tcpHealthCheck(String ip, int port) {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, port), 3000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get all cached container progress data.
     */
    public Map<String, ContainerProgressResponse> getAllProgress() {
        return Map.copyOf(cache);
    }
}
