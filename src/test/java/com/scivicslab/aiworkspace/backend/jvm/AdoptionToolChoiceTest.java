package com.scivicslab.aiworkspace.backend.jvm;

import com.scivicslab.aiworkspace.config.AiWorkspaceConfig;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit test for {@code CompanionJars_260912_oo01}: when two tiles run the same jar, an adopted
 * process is credited to the tile whose jvmArgs it carries — the plugin-bearing tile for a
 * process started with -Dchat-ui.plugins, the plain tile otherwise.
 */
class AdoptionToolChoiceTest {

    private static AiWorkspaceConfig.ToolDefinition tool(String name, List<String> jvmArgs) {
        return new AiWorkspaceConfig.ToolDefinition(name, "chat-ui-with-audit-trail.jar", 28030,
                false, false, false, null, jvmArgs, List.of(), "scivicslab/x");
    }

    private static final String JAR = "/home/x/works/chat-ui-with-audit-trail.jar";
    private static final AiWorkspaceConfig.ToolDefinition LOCAL =
            tool("local", List.of("-Dchat-ui.plugins=/home/x/works/web.jar"));
    private static final AiWorkspaceConfig.ToolDefinition CLOUD =
            tool("cloud", List.of("-Dchat-ui.plugins=/home/x/works/web.jar,/home/x/works/harness.jar"));
    private static final AiWorkspaceConfig.ToolDefinition PLAIN = tool("plain", null);
    private static final List<Map.Entry<String, AiWorkspaceConfig.ToolDefinition>> TOOLS =
            List.of(Map.entry(JAR, PLAIN), Map.entry(JAR, LOCAL), Map.entry(JAR, CLOUD));

    @Test
    void pickToolForProcess_matchesTheTileWhoseJvmArgsTheProcessCarries() {
        assertEquals("cloud", JvmBackend.pickToolForProcess(TOOLS, new String[] {
                "-Dchat-ui.plugins=/home/x/works/web.jar,/home/x/works/harness.jar", "-Dquarkus.http.port=28031", "-jar", JAR}).name());
        assertEquals("local", JvmBackend.pickToolForProcess(TOOLS, new String[] {
                "-Dchat-ui.plugins=/home/x/works/web.jar", "-Dquarkus.http.port=28030", "-jar", JAR}).name());
        assertEquals("plain", JvmBackend.pickToolForProcess(TOOLS, new String[] {
                "-Dquarkus.http.port=28014", "-jar", JAR}).name());
    }

    @Test
    void pickToolForProcess_otherJar_isNull() {
        assertNull(JvmBackend.pickToolForProcess(TOOLS, new String[] {"-jar", "/home/x/works/other.jar"}));
    }
}
