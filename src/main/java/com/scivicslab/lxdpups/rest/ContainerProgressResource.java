package com.scivicslab.lxdpups.rest;

import com.scivicslab.lxdpups.config.PortalConfigLoader;
import com.scivicslab.lxdpups.model.ContainerProgressResponse;
import com.scivicslab.lxdpups.model.ServiceProgressSummary;
import com.scivicslab.lxdpups.service.ProcessManager;
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

    @GET
    public ContainerProgressResponse getProgress() {
        var config = configLoader.getConfig();
        var services = new ArrayList<ServiceProgressSummary>();
        for (var svc : config.getManagementServices()) {
            if (!svc.isEnabled()) continue;
            var status = processManager.getStatus(svc.getName());
            var progress = processManager.getProgress(svc.getName());
            services.add(new ServiceProgressSummary(
                    svc.getName(), svc.getDescription(), svc.getPort(),
                    status.name(), progress.phase(), progress.done(), progress.success()
            ));
        }
        return new ContainerProgressResponse(config.getTitle(), services);
    }
}
