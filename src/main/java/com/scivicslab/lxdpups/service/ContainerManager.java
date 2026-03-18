package com.scivicslab.lxdpups.service;

import com.scivicslab.lxdpups.exec.CommandResult;
import com.scivicslab.lxdpups.exec.CommandRunner;
import com.scivicslab.lxdpups.model.ContainerInfo;
import com.scivicslab.lxdpups.model.ServiceStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages worker LXC containers via lxc commands.
 */
@ApplicationScoped
public class ContainerManager {

    private static final Logger LOG = Logger.getLogger(ContainerManager.class.getName());
    private final CommandRunner runner = new CommandRunner();
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * Builds a qualified name like "remote:container" or just "container" for local.
     */
    public static String qualify(String remote, String container) {
        if (remote == null || remote.isEmpty() || "local".equals(remote)) {
            return container;
        }
        return remote + ":" + container;
    }

    /**
     * Lists all containers, optionally filtered by remote.
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
                var name = (String) c.get("name");
                var status = (String) c.get("status");
                var ip = extractIpv4(c);
                list.add(new ContainerInfo(name, status, remote != null ? remote : "local",
                        ip, "", "", List.of()));
            }
            return list;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to parse lxc list output", e);
            return List.of();
        }
    }

    public boolean launch(String template, String name, String remote) {
        var qName = qualify(remote, name);
        LOG.info("Launching container: " + qName + " from " + template);
        var result = runner.run(List.of("lxc", "launch", template, qName));
        if (!result.success()) {
            LOG.warning("Failed to launch " + qName + ": " + result.stderr());
        }
        return result.success();
    }

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

    public boolean delete(String name, String remote) {
        var qName = qualify(remote, name);
        LOG.info("Deleting container: " + qName);
        return runner.run(List.of("lxc", "delete", qName)).success();
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

    /**
     * Execute a command inside a container.
     */
    public CommandResult exec(String name, String remote, String... cmd) {
        var qName = qualify(remote, name);
        var command = new ArrayList<>(List.of("lxc", "exec", qName, "--"));
        command.addAll(List.of(cmd));
        return runner.run(command);
    }

    /**
     * Check the status of a systemd unit inside a container.
     */
    public ServiceStatus serviceStatus(String container, String remote, String unit) {
        var result = exec(container, remote, "systemctl", "is-active", unit);
        return switch (result.stdout().strip()) {
            case "active" -> ServiceStatus.ACTIVE;
            case "inactive" -> ServiceStatus.INACTIVE;
            case "failed" -> ServiceStatus.FAILED;
            default -> ServiceStatus.UNKNOWN;
        };
    }

    public boolean serviceStart(String container, String remote, String unit) {
        return exec(container, remote, "systemctl", "start", unit).success();
    }

    public boolean serviceStop(String container, String remote, String unit) {
        return exec(container, remote, "systemctl", "stop", unit).success();
    }

    /**
     * Extract the first IPv4 address from lxc list JSON state.network.
     * Path: state -> network -> eth0 -> addresses[] -> {family: "inet", address: "..."}
     */
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
