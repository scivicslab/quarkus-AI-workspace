package com.scivicslab.aiworkspace.backend.jvm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.scivicslab.aiworkspace.config.AiWorkspaceConfig;
import com.scivicslab.aiworkspace.config.PortalPort;

/**
 * Every tool AI-workspace launches is told where AI-workspace's node is, in {@code AI_WORKSPACE_NODE}, whatever the
 * tool: AI-workspace keeps no list of which tools are nodes; a tool that is one reads it and announces itself
 * (ActorNodesForWorkspaceAndPortal_261118_oo01, step 3; decision 4).
 */
class ToolEnvironmentTest {

    private static Map<String, String> environmentOf(String tool) {
        ProcessSupervisor s = new ProcessSupervisor(new AiWorkspaceConfig.ToolDefinition(tool, tool + ".jar", 28010,
                false, false, false, List.of(), List.of(), List.of(), null), 28010, Map.of());
        Map<String, String> env = new HashMap<>();
        s.fillEnvironment(env);
        return env;
    }

    @Test
    void everyToolIsToldWhereTheWorkspaceNodeIs() {
        int http = PortalPort.number();
        String expected = "ai-workspace-" + http + "@127.0.0.1:" + (http + 1000);
        for (String tool : List.of("html-saurus", "turing-workflow-editor", "chat-ui-with-audit-trail-local-llm", "frame-viewer")) {
            Map<String, String> env = environmentOf(tool);
            assertThat(env).as(tool).containsEntry("AI_WORKSPACE_NODE", expected);
            assertThat(env).as(tool).containsEntry("AI_WORKSPACE_URL", "http://localhost:" + http);
        }
    }
}
