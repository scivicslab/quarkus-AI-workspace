package com.scivicslab.aiworkspace.milestone;

import com.scivicslab.aiworkspace.model.DashboardModel;
import com.scivicslab.aiworkspace.model.SessionView;
import com.scivicslab.aiworkspace.spi.ServiceBackend;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Exposes the current milestone status to external systems (lxd-pups, k8s-pups).
 *
 * GET /api/milestone
 */
@Path("/api/milestone")
@Produces(MediaType.APPLICATION_JSON)
public class MilestoneResource {

    @Inject
    ServiceBackend backend;

    @GET
    public MilestoneResponse getMilestone() {
        DashboardModel model = backend.getDashboardModel();

        List<SessionView> all = new ArrayList<>();
        all.addAll(model.managementServices());
        all.addAll(model.activeSessions());

        MilestoneEvaluator.Result result = MilestoneEvaluator.evaluate(all);

        return new MilestoneResponse(
            result.milestone(),
            result.conditions(),
            all,
            Instant.now().toString()
        );
    }

    public record MilestoneResponse(
        String milestone,
        Map<String, Boolean> conditions,
        List<SessionView> sessions,
        String timestamp
    ) {}
}
