package com.scivicslab.lxdpups.actor;

import com.scivicslab.lxdpups.service.ContainerManager;
import com.scivicslab.pojoactor.core.ActorRef;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Worker actor that executes a single lxc launch operation.
 * <p>
 * Lifecycle: S0 -> S1 (launch + config + restart + wait for service) -> S2.
 * On failure at any point, cleans up the container and returns to S0.
 * </p>
 */
public class LaunchWorkerActor {

    private static final Logger LOG = Logger.getLogger(LaunchWorkerActor.class.getName());
    private static final Duration LXC_TIMEOUT = Duration.ofMinutes(5);
    private static final int HEALTH_CHECK_INTERVAL_MS = 2000;
    private static final int HEALTH_CHECK_MAX_ATTEMPTS = 30; // 60 seconds

    /**
     * Port to check per image type.
     */
    private static final Map<String, Integer> IMAGE_PORTS = Map.of(
            "lxd-pups/ai-tools", 16080,
            "lxd-pups/claude", 16120,
            "lxd-pups/jupyter", 16900,
            "lxd-pups/guacamole", 16901
    );

    private final ContainerManager containerManager;
    private final LaunchProgress progress;
    private final ActorRef<ContainerSupervisorActor> supervisor;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public LaunchWorkerActor(ContainerManager containerManager, LaunchProgress progress,
                             ActorRef<ContainerSupervisorActor> supervisor) {
        this.containerManager = containerManager;
        this.progress = progress;
        this.supervisor = supervisor;
    }

    /**
     * Execute the full launch sequence: lxc launch + config + restart + wait for service.
     * On any failure, force-deletes the container to prevent zombies.
     */
    public void doLaunch(String template, String name, String remote) {
        var qName = ContainerManager.qualify(remote, name);
        boolean containerCreated = false;
        try {
            // Check if container already exists
            var checkResult = containerManager.runCommand(
                    List.of("lxc", "info", qName));
            if (checkResult.success()) {
                progress.addMessage("Container '" + name + "' already exists.");
                failAndNotify(name);
                return;
            }

            progress.setPhase("launching");
            progress.addMessage("Creating container from " + template + " ...");

            var result = containerManager.execCommand(
                    List.of("lxc", "launch", template, qName), LXC_TIMEOUT);
            if (!result.success()) {
                progress.addMessage("Failed (exit " + result.exitCode() + ")");
                failAndNotify(name);
                return;
            }
            containerCreated = true;
            progress.addMessage("Container created.");

            progress.setPhase("configuring");
            progress.addMessage("Setting labels ...");
            containerManager.execCommand(
                    List.of("lxc", "config", "set", qName, "user.lxd-pups", "worker"),
                    LXC_TIMEOUT);
            containerManager.execCommand(
                    List.of("lxc", "config", "set", qName, "user.lxd-pups-image", template),
                    LXC_TIMEOUT);

            var hostHome = System.getProperty("user.home");
            var hostUid = containerManager.getHostUid();
            progress.addMessage("Binding " + hostHome + " -> /home/ubuntu (uid " + hostUid + " -> 1000)");
            containerManager.execCommand(List.of("lxc", "config", "set", qName, "raw.idmap",
                    "both " + hostUid + " 1000"), LXC_TIMEOUT);
            containerManager.execCommand(List.of("lxc", "config", "device", "add", qName, "home",
                    "disk", "source=" + hostHome, "path=/home/ubuntu"), LXC_TIMEOUT);

            // Bind-mount Claude CLI if available on host
            var claudePath = hostHome + "/.local/share/claude";
            if (java.nio.file.Files.isDirectory(java.nio.file.Path.of(claudePath))) {
                progress.addMessage("Binding Claude CLI -> /opt/claude");
                containerManager.execCommand(List.of("lxc", "config", "device", "add", qName, "claude-cli",
                        "disk", "source=" + claudePath, "path=/opt/claude"), LXC_TIMEOUT);
            }

            progress.setPhase("restarting");
            progress.addMessage("Restarting container to apply UID mapping ...");
            containerManager.execCommand(
                    List.of("lxc", "restart", qName), LXC_TIMEOUT);

            // Wait for service to respond
            progress.setPhase("waiting");
            int port = IMAGE_PORTS.getOrDefault(template, 16080);
            progress.addMessage("Waiting for service on port " + port + " ...");

            // Need IP address - poll lxc list until we get it
            String ip = waitForIp(name, remote);
            if (ip == null) {
                progress.addMessage("Timed out waiting for IP address.");
                cleanup(name, remote);
                failAndNotify(name);
                return;
            }

            boolean serviceUp = waitForService(template, ip, port);
            if (!serviceUp) {
                progress.addMessage("Timed out waiting for service on port " + port + ".");
                cleanup(name, remote);
                failAndNotify(name);
                return;
            }

            progress.addMessage("Service responding. Container ready.");
            progress.setPhase("running");
            progress.complete(true);
            supervisor.tell(s -> s.onLaunchComplete(name, true));

        } catch (Exception e) {
            progress.addMessage("Error: " + e.getMessage());
            if (containerCreated) {
                cleanup(name, remote);
            }
            failAndNotify(name);
            LOG.warning("Launch failed for " + name + ": " + e.getMessage());
        }
    }

    /**
     * Wait for the container to get an IP address (max 30 seconds).
     */
    private String waitForIp(String name, String remote) {
        for (int i = 0; i < 15; i++) {
            try { Thread.sleep(2000); } catch (InterruptedException e) { return null; }
            var containers = containerManager.list(remote);
            for (var c : containers) {
                if (name.equals(c.name()) && c.ip() != null && !c.ip().isEmpty()) {
                    progress.addMessage("Container IP: " + c.ip());
                    return c.ip();
                }
            }
        }
        return null;
    }

    /**
     * Wait for the main service port to respond.
     * ai-tools: HTTP GET to child portal.
     * jupyter/guacamole: TCP connect.
     */
    private boolean waitForService(String template, String ip, int port) {
        boolean useHttp = "lxd-pups/ai-tools".equals(template)
                || "lxd-pups/claude".equals(template);
        String healthPath = "lxd-pups/claude".equals(template) ? "/api/config" : "/api/progress";
        for (int i = 0; i < HEALTH_CHECK_MAX_ATTEMPTS; i++) {
            try { Thread.sleep(HEALTH_CHECK_INTERVAL_MS); } catch (InterruptedException e) { return false; }
            if (useHttp) {
                if (httpHealthCheck(ip, port, healthPath)) return true;
            } else {
                if (tcpHealthCheck(ip, port)) return true;
            }
        }
        return false;
    }

    private boolean httpHealthCheck(String ip, int port) {
        return httpHealthCheck(ip, port, "/api/progress");
    }

    private boolean httpHealthCheck(String ip, int port, String path) {
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create("http://" + ip + ":" + port + path))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean tcpHealthCheck(String ip, int port) {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, port), 3000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Force-delete the container on failure to prevent S4 (Stopped) zombies.
     */
    private void cleanup(String name, String remote) {
        progress.addMessage("Cleaning up failed container ...");
        containerManager.forceDelete(name, remote);
    }

    private void failAndNotify(String name) {
        progress.complete(false);
        supervisor.tell(s -> s.onLaunchComplete(name, false));
    }
}
