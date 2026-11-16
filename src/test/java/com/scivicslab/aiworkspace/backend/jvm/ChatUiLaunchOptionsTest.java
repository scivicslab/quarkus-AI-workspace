package com.scivicslab.aiworkspace.backend.jvm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.scivicslab.aiworkspace.config.AiWorkspaceConfig;
import com.scivicslab.aiworkspace.config.PortalPort;

/**
 * A chat-ui-with-audit-trail instance launched here is told its node name, its actor port and where
 * this portal is, so that it can find its peers (ChatUiPeers_261116_oo01). Other tools are not.
 */
class ChatUiLaunchOptionsTest {

    private static AiWorkspaceConfig.ToolDefinition tool(String name, String jar, List<String> jvmArgs) {
        return new AiWorkspaceConfig.ToolDefinition(name, jar, 28030, false, false, false,
                null, jvmArgs, null, null);
    }

    private static List<String> command(AiWorkspaceConfig.ToolDefinition tool, int port) {
        return new ProcessSupervisor(tool, port, Map.of()).buildCommand();
    }

    @Test
    void aChatUiInstanceIsGivenItsNodeNameActorPortAndThePortalsUrl() {
        List<String> cmd = command(tool("chat-ui-with-audit-trail-local-llm", "chat-ui-with-audit-trail.jar",
                List.of("-Xmx2g")), 28033);
        assertThat(cmd).contains(
                "-Dchat-ui.node-name=chat-ui-28033",
                "-Dchat-ui.actor-port=29033",
                "-Dchat-ui.workspace-url=http://localhost:" + PortalPort.number());
        // JVM options, so they come before -jar
        assertThat(cmd.indexOf("-Dchat-ui.actor-port=29033")).isLessThan(cmd.indexOf("-jar"));
    }

    @Test
    void anActorPortOrNodeNameTheTileAlreadyGivesIsKept() {
        List<String> cmd = command(tool("chat-ui-with-audit-trail-cloud-llm", "chat-ui-with-audit-trail.jar",
                List.of("-Dchat-ui.actor-port=30500", "-Dchat-ui.node-name=gateway")), 28040);
        assertThat(cmd.stream().filter(a -> a.startsWith("-Dchat-ui.actor-port=")).toList())
                .containsExactly("-Dchat-ui.actor-port=30500");
        assertThat(cmd.stream().filter(a -> a.startsWith("-Dchat-ui.node-name=")).toList())
                .containsExactly("-Dchat-ui.node-name=gateway");
        assertThat(cmd).contains("-Dchat-ui.workspace-url=http://localhost:" + PortalPort.number());
    }

    @Test
    void otherToolsAreNotGivenTheseOptions() {
        List<String> cmd = command(tool("turing-workflow-editor", "turing-workflow-editor.jar", List.of()), 28120);
        assertThat(cmd).noneMatch(a -> a.startsWith("-Dchat-ui."));
    }
}
