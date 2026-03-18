package com.scivicslab.lxdpups.service;

import com.scivicslab.lxdpups.config.PortalConfigLoader;
import com.scivicslab.lxdpups.model.HostService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.logging.Logger;

/**
 * Manages host services via direct process management.
 * Delegates to ProcessManager for starting, stopping, and status queries.
 */
@ApplicationScoped
public class HostServiceManager {

    private static final Logger LOG = Logger.getLogger(HostServiceManager.class.getName());

    @Inject
    PortalConfigLoader configLoader;

    @Inject
    ProcessManager processManager;

    public List<HostService> getAllStatuses() {
        return processManager.getAllStatuses(configLoader.getConfig().getManagementServices());
    }

    /**
     * Start a management service by name (synchronous).
     */
    public boolean start(String name) {
        var svc = findService(name);
        if (svc == null) {
            LOG.warning("Unknown management service: " + name);
            return false;
        }
        LOG.info("Starting management service: " + name);
        return processManager.start(svc);
    }

    /**
     * Start a management service asynchronously with progress tracking.
     */
    public boolean startAsync(String name) {
        var svc = findService(name);
        if (svc == null) {
            LOG.warning("Unknown management service: " + name);
            return false;
        }
        LOG.info("Starting management service (async): " + name);
        processManager.startAsync(svc);
        return true;
    }

    /**
     * Stop a management service by name.
     */
    public boolean stop(String name) {
        LOG.info("Stopping management service: " + name);
        return processManager.stop(name);
    }

    /**
     * Restart a management service by name (stop then start).
     */
    public boolean restart(String name) {
        LOG.info("Restarting management service: " + name);
        var svc = findService(name);
        if (svc == null) {
            LOG.warning("Unknown management service: " + name);
            return false;
        }
        processManager.stop(name);
        return processManager.start(svc);
    }

    private com.scivicslab.lxdpups.config.PortalConfig.ManagementService findService(String name) {
        return configLoader.getConfig().getManagementServices().stream()
                .filter(s -> name.equals(s.getName()))
                .findFirst()
                .orElse(null);
    }
}
