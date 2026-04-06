package com.scivicslab.lxdpups.service;

import com.scivicslab.lxdpups.exec.CommandResult;
import com.scivicslab.lxdpups.exec.CommandRunner;
import com.scivicslab.lxdpups.model.ContainerInfo;
import com.scivicslab.lxdpups.model.ImageInfo;
import com.scivicslab.lxdpups.model.ServiceStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Low-level LXC command wrapper (analogous to K8sApiClient in k8s-pups).
 * <p>
 * All orchestration and state management is handled by actors.
 * This class only executes lxc commands and parses results.
 * </p>
 */
@ApplicationScoped
public class ContainerManager {

    private static final Logger LOG = Logger.getLogger(ContainerManager.class.getName());
    private final CommandRunner runner = new CommandRunner();
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // ── Public command execution (used by actors) ──

    /**
     * Run an external command with default timeout (30s).
     */
    public CommandResult runCommand(List<String> command) {
        return runner.run(command);
    }

    /**
     * Run an external command with custom timeout.
     */
    public CommandResult runCommand(List<String> command, Duration timeout) {
        return runner.run(command, timeout);
    }

    /**
     * Execute a command without capturing output (stdin/stdout/stderr -> /dev/null).
     * Use for commands where only exit code matters (lxc launch, lxc restart, lxc config set).
     */
    public CommandResult execCommand(List<String> command, Duration timeout) {
        return runner.exec(command, timeout);
    }

    /**
     * Get the current user's UID from the OS.
     */
    public String getHostUid() {
        var result = runner.run(List.of("id", "-u"));
        if (result.success()) {
            return result.stdout().strip();
        }
        return "1000";
    }

    /**
     * Get the current host username.
     */
    public String getHostUser() {
        return System.getProperty("user.name");
    }

    /**
     * Builds a qualified name like "remote:container" or just "container" for local.
     */
    public static String qualify(String remote, String container) {
        if (remote == null || remote.isEmpty() || "local".equals(remote)) {
            return container;
        }
        return remote + ":" + container;
    }

    // ── Container queries ──

    /**
     * Lists worker containers (filtered by user.lxd-pups=worker label).
     */
    @SuppressWarnings("unchecked")
    public List<ContainerInfo> list(String remote) {
        var qualified = (remote == null || remote.isEmpty() || "local".equals(remote))
                ? List.of("lxc", "list", "--format", "json")
                : List.of("lxc", "list", remote + ":", "--format", "json");

        var result = runner.run(qualified);
        if (!result.success()) {
            LOG.warning("Failed to list containers: " + result.stderr());
            return List.of();
        }

        try {
            List<Map<String, Object>> containers = mapper.readValue(
                    result.stdout(), new TypeReference<>() {});
            var list = new ArrayList<ContainerInfo>();
            for (var c : containers) {
                @SuppressWarnings("unchecked")
                var config = (Map<String, Object>) c.getOrDefault("config", Map.of());
                if (!"worker".equals(config.get("user.lxd-pups"))) {
                    continue;
                }
                var name = (String) c.get("name");
                var status = (String) c.get("status");
                var ip = extractIpv4(c);
                var image = (String) config.getOrDefault("user.lxd-pups-image", "");
                list.add(new ContainerInfo(name, status, remote != null ? remote : "local",
                        ip, image, "", List.of()));
            }
            return list;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to parse lxc list output", e);
            return List.of();
        }
    }

    /**
     * Lists ALL containers (no label filter).
     */
    @SuppressWarnings("unchecked")
    public List<ContainerInfo> listAll(String remote) {
        var qualified = (remote == null || remote.isEmpty() || "local".equals(remote))
                ? List.of("lxc", "list", "--format", "json")
                : List.of("lxc", "list", remote + ":", "--format", "json");

        var result = runner.run(qualified);
        if (!result.success()) {
            LOG.warning("Failed to list containers: " + result.stderr());
            return List.of();
        }

        try {
            List<Map<String, Object>> containers = mapper.readValue(
                    result.stdout(), new TypeReference<>() {});
            var list = new ArrayList<ContainerInfo>();
            for (var c : containers) {
                var name = (String) c.get("name");
                var status = (String) c.get("status");
                var ip = extractIpv4(c);
                @SuppressWarnings("unchecked")
                var config = (Map<String, Object>) c.getOrDefault("config", Map.of());
                var label = (String) config.getOrDefault("user.lxd-pups", "");
                list.add(new ContainerInfo(name, status, remote != null ? remote : "local",
                        ip, label, "", List.of()));
            }
            return list;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to parse lxc list output", e);
            return List.of();
        }
    }

    /**
     * Lists all LXC images.
     */
    @SuppressWarnings("unchecked")
    public List<ImageInfo> listImages() {
        var result = runner.run(List.of("lxc", "image", "list", "--format", "json"));
        if (!result.success()) {
            LOG.warning("Failed to list images: " + result.stderr());
            return List.of();
        }
        try {
            List<Map<String, Object>> images = mapper.readValue(
                    result.stdout(), new TypeReference<>() {});
            var list = new ArrayList<ImageInfo>();
            for (var img : images) {
                var fingerprint = ((String) img.getOrDefault("fingerprint", "")).substring(0, 12);
                var aliases = new ArrayList<String>();
                var aliasList = (List<Map<String, Object>>) img.getOrDefault("aliases", List.of());
                for (var a : aliasList) {
                    aliases.add((String) a.getOrDefault("name", ""));
                }
                var desc = (String) img.getOrDefault("description", "");
                var size = img.containsKey("size") ? ((Number) img.get("size")).longValue() / 1024 / 1024 : 0L;
                var uploaded = (String) img.getOrDefault("uploaded_at", "");
                list.add(new ImageInfo(fingerprint, aliases, desc, size, uploaded));
            }
            return list;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to parse lxc image list output", e);
            return List.of();
        }
    }

    // ── Container operations ──

    public boolean start(String name, String remote) {
        var qName = qualify(remote, name);
        LOG.info("Starting container: " + qName);
        return runner.run(List.of("lxc", "start", qName)).success();
    }

    public boolean stop(String name, String remote) {
        var qName = qualify(remote, name);
        LOG.info("Stopping container: " + qName);
        return runner.run(List.of("lxc", "stop", qName)).success();
    }

    /**
     * Stop and delete a container (Stop = Delete per lifecycle spec).
     * Uses --force to handle containers that are already stopped.
     */
    public boolean stopAndDelete(String name, String remote) {
        var qName = qualify(remote, name);
        LOG.info("Stopping and deleting container: " + qName);
        runner.run(List.of("lxc", "stop", qName));
        return runner.run(List.of("lxc", "delete", qName, "--force")).success();
    }

    public boolean delete(String name, String remote) {
        var qName = qualify(remote, name);
        LOG.info("Deleting container: " + qName);
        return runner.run(List.of("lxc", "delete", qName)).success();
    }

    /**
     * Force-delete a container (for cleanup on launch failure).
     */
    public boolean forceDelete(String name, String remote) {
        var qName = qualify(remote, name);
        LOG.info("Force-deleting container: " + qName);
        return runner.run(List.of("lxc", "delete", qName, "--force")).success();
    }

    public boolean snapshot(String name, String snapName, String remote) {
        var qName = qualify(remote, name);
        LOG.info("Snapshot: " + qName + " -> " + snapName);
        return runner.run(List.of("lxc", "snapshot", qName, snapName)).success();
    }

    public boolean restore(String name, String snapName, String remote) {
        var qName = qualify(remote, name);
        LOG.info("Restore: " + qName + " -> " + snapName);
        return runner.run(List.of("lxc", "restore", qName, snapName)).success();
    }

    // ── Container exec ──

    public CommandResult exec(String name, String remote, String... cmd) {
        var qName = qualify(remote, name);
        var command = new ArrayList<>(List.of("lxc", "exec", qName, "--"));
        command.addAll(List.of(cmd));
        return runner.run(command);
    }

    public ServiceStatus serviceStatus(String container, String remote, String unit) {
        var result = exec(container, remote, "systemctl", "is-active", unit);
        return ServiceStatus.fromSystemdState(result.stdout().strip());
    }

    public boolean serviceStart(String container, String remote, String unit) {
        return exec(container, remote, "systemctl", "start", unit).success();
    }

    public boolean serviceStop(String container, String remote, String unit) {
        return exec(container, remote, "systemctl", "stop", unit).success();
    }

    // ── Private helpers ──

    @SuppressWarnings("unchecked")
    private String extractIpv4(Map<String, Object> container) {
        try {
            var state = (Map<String, Object>) container.get("state");
            if (state == null) return "";
            var network = (Map<String, Object>) state.get("network");
            if (network == null) return "";
            for (var iface : network.values()) {
                var ifaceMap = (Map<String, Object>) iface;
                var addresses = (List<Map<String, Object>>) ifaceMap.get("addresses");
                if (addresses == null) continue;
                for (var addr : addresses) {
                    if ("inet".equals(addr.get("family")) && !"lo".equals(addr.get("scope"))) {
                        return (String) addr.get("address");
                    }
                }
            }
        } catch (Exception e) {
            LOG.fine("Could not extract IPv4: " + e.getMessage());
        }
        return "";
    }
}
