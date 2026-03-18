package com.scivicslab.lxdpups.rest;

import com.scivicslab.lxdpups.config.PortalConfigLoader;
import com.scivicslab.lxdpups.model.ContainerProgressResponse;
import com.scivicslab.lxdpups.model.ServiceProgressSummary;
import com.scivicslab.lxdpups.service.ProcessManager;
import com.scivicslab.lxdpups.service.ToolInstanceManager;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.ArrayList;

/**
 * Exposes /api/progress for the host portal to poll.
 * Returns aggregate status of all management services in this portal.
 */
@Path("/api/progress")
@Produces(MediaType.APPLICATION_JSON)
public class ContainerProgressResource {

    @Inject
    PortalConfigLoader configLoader;

    @Inject
    ProcessManager processManager;

    @Inject
    ToolInstanceManager toolInstanceManager;

    @GET
    public ContainerProgressResponse getProgress() {
        var config = configLoader.getConfig();
        var services = new ArrayList<ServiceProgressSummary>();

        // Management services
        for (var svc : config.getManagementServices()) {
            if (!svc.isEnabled()) continue;
            var status = processManager.getStatus(svc.getName(), svc.getPort());
            var progress = processManager.getProgress(svc.getName());
            services.add(new ServiceProgressSummary(
                    svc.getName(), svc.getDescription(), svc.getPort(),
                    status.name(), progress.phase(), progress.done(), progress.success()
            ));
        }

        // Tool instances
        for (var inst : toolInstanceManager.getRunningInstances()) {
            services.add(new ServiceProgressSummary(
                    inst.toolName(), inst.description(), inst.port(),
                    inst.status().name(), "running", true, true
            ));
        }

        return new ContainerProgressResponse(config.getTitle(), services);
    }
}
