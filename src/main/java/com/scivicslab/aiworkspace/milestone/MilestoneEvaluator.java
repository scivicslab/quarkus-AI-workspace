package com.scivicslab.aiworkspace.milestone;

import com.scivicslab.aiworkspace.model.SessionState;
import com.scivicslab.aiworkspace.model.SessionView;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes the current milestone from a list of sessions.
 * Pure function — no side effects, easy to unit-test.
 *
 * Milestone order (each requires the previous to be satisfied):
 *   S0               - portal is running (implicit)
 *   S1-gateway-ready - quarkus-mcp-gateway is READY
 *   S2-chat-ready    - at least one quarkus-chat-ui is READY
 *   S3-workflow-ready - turing-workflow-editor is READY
 *   S4-ready         - all of the above
 */
public class MilestoneEvaluator {

    public record Result(String milestone, Map<String, Boolean> conditions) {}

    public static Result evaluate(List<SessionView> sessions) {
        boolean gatewayReady = isReady(sessions, "quarkus-mcp-gateway");
        boolean chatReady    = isReady(sessions, "quarkus-chat-ui");
        boolean workflowReady = isReady(sessions, "turing-workflow-editor");

        Map<String, Boolean> conditions = new LinkedHashMap<>();
        conditions.put("S1-gateway-ready",  gatewayReady);
        conditions.put("S2-chat-ready",     gatewayReady && chatReady);
        conditions.put("S3-workflow-ready", gatewayReady && chatReady && workflowReady);
        conditions.put("S4-ready",          gatewayReady && chatReady && workflowReady);

        String milestone = "S0";
        if (Boolean.TRUE.equals(conditions.get("S4-ready")))          milestone = "S4-ready";
        else if (Boolean.TRUE.equals(conditions.get("S3-workflow-ready"))) milestone = "S3-workflow-ready";
        else if (Boolean.TRUE.equals(conditions.get("S2-chat-ready")))     milestone = "S2-chat-ready";
        else if (Boolean.TRUE.equals(conditions.get("S1-gateway-ready")))  milestone = "S1-gateway-ready";

        return new Result(milestone, conditions);
    }

    private static boolean isReady(List<SessionView> sessions, String toolName) {
        return sessions.stream()
            .anyMatch(s -> toolName.equals(s.toolName()) && s.state() == SessionState.READY);
    }
}
