package com.scivicslab.lxdpups.service;

import com.scivicslab.lxdpups.config.PortalConfig;
import com.scivicslab.lxdpups.config.PortalConfigLoader;
import com.scivicslab.lxdpups.exec.CommandRunner;
import com.scivicslab.lxdpups.model.HostService;
import com.scivicslab.lxdpups.model.ServiceStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Manages host services (MCP Gateway, predict-ja, predict-en) via systemctl.
 */
@ApplicationScoped
public class HostServiceManager {

    private static final Logger LOG = Logger.getLogger(HostServiceManager.class.getName());
    private final CommandRunner runner = new CommandRunner();

    @Inject
    PortalConfigLoader configLoader;

    public List<HostService> getAllStatuses() {
        var services = new ArrayList<HostService>();
        for (var svc : configLoader.getConfig().getManagementServices()) {
            if (!svc.isEnabled()) continue;
            var status = getStatus(svc.getUnit());
            services.add(new HostService(
                    svc.getName(), svc.getUnit(), svc.getPort(),
                    svc.getDescription(), svc.getUi(), status));
        }
        return services;
    }

    public ServiceStatus getStatus(String unit) {
        var result = runner.run(List.of("systemctl", "is-active", unit));
        return switch (result.stdout().strip()) {
            case "active" -> ServiceStatus.ACTIVE;
            case "inactive" -> ServiceStatus.INACTIVE;
            case "failed" -> ServiceStatus.FAILED;
            default -> ServiceStatus.UNKNOWN;
        };
    }

    public boolean start(String unit) {
        LOG.info("Starting host service: " + unit);
        var result = runner.run(List.of("systemctl", "start", unit));
        if (!result.success()) {
            LOG.warning("Failed to start " + unit + ": " + result.stderr());
        }
        return result.success();
    }

    public boolean stop(String unit) {
        LOG.info("Stopping host service: " + unit);
        var result = runner.run(List.of("systemctl", "stop", unit));
        if (!result.success()) {
            LOG.warning("Failed to stop " + unit + ": " + result.stderr());
        }
        return result.success();
    }

    public boolean restart(String unit) {
        LOG.info("Restarting host service: " + unit);
        var result = runner.run(List.of("systemctl", "restart", unit));
        if (!result.success()) {
            LOG.warning("Failed to restart " + unit + ": " + result.stderr());
        }
        return result.success();
    }
}
