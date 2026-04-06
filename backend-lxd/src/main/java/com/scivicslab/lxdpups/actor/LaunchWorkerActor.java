package com.scivicslab.lxdpups.actor;

import com.scivicslab.lxdpups.service.ContainerManager;
import com.scivicslab.pojoactor.core.ActorRef;

import com.scivicslab.lxdpups.model.HealthCheckConfig;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
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

    private final ContainerManager containerManager;
    private final LaunchProgress progress;
    private final ActorRef<ContainerSupervisorActor> supervisor;

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

            var hostUser = System.getProperty("user.name");
            var hostHome = System.getProperty("user.home");
            var hostUid = containerManager.getHostUid();
            var containerHome = "/home/" + hostUser;
            progress.addMessage("Mapping host user '" + hostUser + "' (uid " + hostUid + ") -> container uid 1000");
            containerManager.execCommand(List.of("lxc", "config", "set", qName, "raw.idmap",
                    "both " + hostUid + " 1000"), LXC_TIMEOUT);
            containerManager.execCommand(List.of("lxc", "config", "device", "add", qName, "home",
                    "disk", "source=" + hostHome, "path=" + containerHome), LXC_TIMEOUT);

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

            // Rename default 'ubuntu' user to match host user
            progress.addMessage("Renaming container user 'ubuntu' -> '" + hostUser + "' ...");
            runChecked(qName, "usermod -l", "usermod", "-l", hostUser, "ubuntu");
            runChecked(qName, "groupmod -n", "groupmod", "-n", hostUser, "ubuntu");
            runChecked(qName, "usermod -d -m", "usermod", "-d", containerHome, "-m", hostUser);
            runChecked(qName, "usermod -c", "usermod", "-c", hostUser, hostUser);

            // Prevent cloud-init from recreating the 'ubuntu' user
            runChecked(qName, "cloud-init override",
                    "bash", "-c", "echo 'system_info: { default_user: { name: " + hostUser + " } }'"
                            + " > /etc/cloud/cloud.cfg.d/99-rename-user.cfg");

            // Rewrite image-specific services that hardcode 'ubuntu' user
            if ("lxd-pups/guacamole".equals(template)) {
                // The image uses guacamole-desktop.service + start-guacamole-desktop.sh
                // which hardcode 'ubuntu'. Replace all occurrences with the renamed user.
                runChecked(qName, "update guacamole startup script",
                        "bash", "-c", "sed -i 's/ubuntu/" + hostUser + "/g'"
                                + " /usr/local/bin/start-guacamole-desktop.sh");
                // Update guacamole user-mapping.xml
                runChecked(qName, "update guacamole user-mapping",
                        "bash", "-c", "sed -i 's/username=\"ubuntu\"/username=\"" + hostUser + "\"/g'"
                                + " /etc/guacamole/user-mapping.xml");
                // Restart the all-in-one service to pick up changes
                runChecked(qName, "restart guacamole-desktop",
                        "bash", "-c", "systemctl restart guacamole-desktop");
            }

            if ("lxd-pups/jupyter".equals(template)) {
                var jupyterUnit = "[Unit]\n"
                        + "Description=Jupyter Lab\n"
                        + "After=network-online.target\n"
                        + "Wants=network-online.target\n\n"
                        + "[Service]\n"
                        + "Type=simple\n"
                        + "User=" + hostUser + "\n"
                        + "Group=" + hostUser + "\n"
                        + "Environment=JULIA_DEPOT_PATH=/opt/julia-depot\n"
                        + "Environment=PATH=/usr/local/bin:/usr/bin:/bin:/opt/julia/bin\n"
                        + "Environment=HOME=" + containerHome + "\n"
                        + "WorkingDirectory=" + containerHome + "\n"
                        + "ExecStart=/usr/local/bin/jupyter lab --ip=0.0.0.0 --port=16900 --no-browser"
                        + " --NotebookApp.token= --NotebookApp.password=\n"
                        + "Restart=on-failure\n"
                        + "RestartSec=5\n\n"
                        + "[Install]\n"
                        + "WantedBy=multi-user.target\n";
                runChecked(qName, "write jupyter service",
                        "bash", "-c", "cat > /etc/systemd/system/jupyter-lab.service << 'UNIT'\n"
                                + jupyterUnit + "UNIT");
                runChecked(qName, "reload + restart jupyter",
                        "bash", "-c", "systemctl daemon-reload && systemctl restart jupyter-lab");
            }

            // Verify user rename
            var verifyUser = containerManager.runCommand(
                    List.of("lxc", "exec", qName, "--", "id", hostUser));
            progress.addMessage("Verify user: " + verifyUser.stdout().strip());

            // Update all systemd units that reference 'ubuntu'
            runChecked(qName, "update systemd units",
                    "bash", "-c", "find /etc/systemd/system -name '*.service'"
                            + " -exec sed -i 's|User=ubuntu|User=" + hostUser + "|g;"
                            + "s|/home/ubuntu|/home/" + hostUser + "|g' {} +");
            runChecked(qName, "daemon-reload", "systemctl", "daemon-reload");

            // Wait for service to respond
            progress.setPhase("waiting");
            HealthCheckConfig healthCheck = HealthCheckConfig.forImage(template);
            int port = healthCheck.port();
            progress.addMessage("Waiting for service on port " + port + " ...");

            // Need IP address - poll lxc list until we get it
            String ip = waitForIp(name, remote);
            if (ip == null) {
                progress.addMessage("Timed out waiting for IP address. Container preserved for investigation.");
                failAndNotify(name);
                return;
            }

            boolean serviceUp = waitForService(template, ip, port);
            if (!serviceUp) {
                progress.addMessage("Timed out waiting for service on port " + port + ". Container preserved for investigation.");
                failAndNotify(name);
                return;
            }

            progress.addMessage("Service responding. Container ready.");
            progress.setPhase("running");
            progress.complete(true);
            supervisor.tell(s -> s.onLaunchComplete(name, true));

        } catch (Exception e) {
            progress.addMessage("Error: " + e.getMessage());
            if (!containerCreated) {
                // Container was never created — nothing to preserve
                progress.addMessage("No container to preserve.");
            } else {
                progress.addMessage("Container preserved for investigation.");
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
     * Wait for the main service port to respond using TCP connect.
     * Health check configuration (port, interval, max attempts) is defined in HealthCheckConfig.
     */
    private boolean waitForService(String template, String ip, int port) {
        HealthCheckConfig config = HealthCheckConfig.forImage(template);
        for (int i = 0; i < config.maxAttempts(); i++) {
            try { Thread.sleep(config.intervalMs()); } catch (InterruptedException e) { return false; }
            if (tcpHealthCheck(ip, port)) return true;
        }
        return false;
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

    /**
     * Run a command inside the container via lxc exec, logging success/failure to progress.
     */
    private void runChecked(String qName, String label, String... cmd) {
        var command = new java.util.ArrayList<>(List.of("lxc", "exec", qName, "--"));
        command.addAll(List.of(cmd));
        var result = containerManager.runCommand(command, LXC_TIMEOUT);
        if (result.success()) {
            progress.addMessage("  [OK] " + label);
        } else {
            progress.addMessage("  [FAIL] " + label + " (exit " + result.exitCode() + "): "
                    + result.stderr().strip());
            LOG.warning(label + " failed (exit " + result.exitCode() + "): " + result.stderr());
        }
    }

    private void failAndNotify(String name) {
        progress.complete(false);
        supervisor.tell(s -> s.onLaunchComplete(name, false));
    }
}
