package com.scivicslab.aiworkspace.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the single-source-of-truth registry: parse the real bundled ai-workspace-tools.yaml
 * and confirm the full launch metadata (port, args, jvmArgs, flags, form params) is loaded — the
 * launch data that previously lived in per-tool plugin classes and in-code bootstrap definitions.
 */
@DisplayName("ToolRegistryLoader — bundled ai-workspace-tools.yaml carries full launch metadata")
class ToolRegistryLoaderTest {

    private static ToolRegistryEntry byName(List<ToolRegistryEntry> all, String name) {
        return all.stream().filter(e -> e.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("missing entry: " + name));
    }

    /**
     * Counts the {@code - name:} entries declared in the bundled registry. Deriving the
     * expectation from the same resource the loader reads means registering a new tool does
     * not require editing this test; the counts diverge only when an entry fails to parse.
     */
    private static long declaredEntryCount() throws IOException {
        try (InputStream is = ToolRegistryLoader.class.getResourceAsStream("/ai-workspace-tools.yaml");
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            return reader.lines().filter(line -> line.matches("\\s*-\\s*name:.*")).count();
        }
    }

    @Test void all_entries_load() throws IOException {
        List<ToolRegistryEntry> all = ToolRegistryLoader.load();
        assertEquals(declaredEntryCount(), all.size(), "expected all registry entries to parse");
    }

    @Test void chat_ui_full_form() {
        ToolRegistryEntry e = byName(ToolRegistryLoader.load(), "quarkus-chat-ui");
        assertEquals("quarkus-chat-ui.jar", e.jarFileName());
        assertEquals("scivicslab/quarkus-chat-ui", e.githubRepo());
        assertEquals(28100, e.defaultPort());
        assertEquals(6, e.params().size(), "chat-ui has 6 form fields");
        AiWorkspaceConfig.ParamDefinition title = e.params().get(0);
        assertEquals("title", title.key());
        assertEquals("chat-ui.title", title.jvmProp());
        AiWorkspaceConfig.ParamDefinition provider = e.params().get(2);
        assertEquals("provider", provider.key());
        assertEquals("select", provider.type());
        assertEquals("chat-ui.provider", provider.jvmProp());
        assertEquals(3, provider.options().size(), "provider select has 3 options");
        assertEquals("claude", provider.options().get(0).value());
        assertTrue(e.params().get(1).workingDir(), "workdir sets the working directory");
    }

    @Test void html_saurus_args_and_argpos() {
        ToolRegistryEntry e = byName(ToolRegistryLoader.load(), "html-saurus");
        assertEquals(List.of("${HOME}/works", "--portal-mode", "--serve", "--port", "${PORT}"), e.args());
        assertEquals(2, e.params().size(), "html-saurus has a Document Root selector and a port field");
        assertEquals(0, e.params().get(0).argPos(), "Document Root replaces args[0]");
        assertEquals("port", e.params().get(1).key());
    }

    @Test void turing_editor_jvmargs() {
        ToolRegistryEntry e = byName(ToolRegistryLoader.load(), "turing-workflow-editor");
        assertEquals(List.of("-Xmx2g"), e.jvmArgs());
        assertEquals(28120, e.defaultPort());
    }

    @Test void code_raptor_single_instance_with_workdir() {
        ToolRegistryEntry e = byName(ToolRegistryLoader.load(), "code-raptor");
        assertTrue(e.singleInstance());
        assertEquals("scivicslab/code-raptor", e.githubRepo());
        assertTrue(e.args().isEmpty());
        assertEquals(2, e.params().size(), "code-raptor has a Working Directory selector and a port field");
        AiWorkspaceConfig.ParamDefinition wd = e.params().get(0);
        assertEquals("workdir", wd.key());
        assertEquals("dir", wd.type());
        assertEquals("code.raptor.works-dir", wd.jvmProp());
        assertEquals("port", e.params().get(1).key());
    }

    @Test void quarkus_gpu_broker_single_instance_with_nodes() {
        ToolRegistryEntry e = byName(ToolRegistryLoader.load(), "quarkus-gpu-broker");
        assertTrue(e.singleInstance());
        assertEquals("scivicslab/quarkus-gpu-broker", e.githubRepo());
        assertEquals(28005, e.defaultPort());
        assertTrue(e.args().isEmpty());
        assertEquals(2, e.params().size(), "quarkus-gpu-broker has a Node IPs field and a port field");
        AiWorkspaceConfig.ParamDefinition nodes = e.params().get(0);
        assertEquals("nodes", nodes.key());
        assertEquals("broker.nodes", nodes.jvmProp());
        assertEquals("port", e.params().get(1).key());
    }

    @Test void library_entry_flagged() {
        ToolRegistryEntry e = byName(ToolRegistryLoader.load(), "Turing-workflow-plugins");
        assertTrue(e.library());
        assertNull(e.jarFileName());
    }

    @Test void turing_workflow_core_is_a_library() {
        ToolRegistryEntry e = byName(ToolRegistryLoader.load(), "turing-workflow");
        assertTrue(e.library());
        assertNull(e.jarFileName());
        assertEquals("scivicslab/Turing-workflow", e.githubRepo());
    }

    @Test void dependsOn_declares_build_order() {
        List<ToolRegistryEntry> all = ToolRegistryLoader.load();
        assertEquals(List.of("turing-workflow", "Turing-workflow-plugins"),
                byName(all, "quarkus-chat-ui3").dependsOn(),
                "chat-ui3 must build turing-workflow then the plugins before itself");
        assertEquals(List.of("turing-workflow", "pluggable-cli"),
                byName(all, "Turing-workflow-plugins").dependsOn(),
                "the plugins compile against turing-workflow and pluggable-cli, so both build first");
        assertTrue(byName(all, "html-saurus").dependsOn().isEmpty(),
                "a tool with no declared dependencies has an empty dependsOn");
        assertEquals(List.of("plugin-llm", "plugin-log-db", "plugin-codedoc"),
                byName(all, "Turing-workflow-plugins").modules(),
                "only the plugin modules the tools consume are built (sibling modules may not build)");
        assertTrue(byName(all, "turing-workflow").modules().isEmpty(),
                "a single-module library builds its whole reactor");
    }

    @Test void chat_ui3_present_and_decoupled_shape() {
        ToolRegistryEntry e = byName(ToolRegistryLoader.load(), "quarkus-chat-ui3");
        assertEquals(28140, e.defaultPort());
        assertEquals(2, e.params().size());
        assertEquals("chatui3.vllm-base-url", e.params().get(0).jvmProp());
        assertFalse(e.autoStart());
        Optional<AiWorkspaceConfig.ParamDefinition> port =
                e.params().stream().filter(p -> p.key().equals("port")).findFirst();
        assertNotNull(port.orElse(null));
    }

    @Test void chat_ui_with_audit_trail_present_and_shape() {
        ToolRegistryEntry e = byName(ToolRegistryLoader.load(), "chat-ui-with-audit-trail-local-llm");
        assertEquals("chat-ui-with-audit-trail.jar", e.jarFileName());
        assertEquals("scivicslab/chat-ui-with-audit-trail", e.githubRepo());
        assertEquals(28030, e.defaultPort());
        assertTrue(e.dependsOn().isEmpty(),
                "every library it needs is a released artifact, so a snapshot build resolves them"
                        + " from Maven Central instead of building them from source first");
        assertEquals(2, e.params().size(), "Local LLM endpoint, port");
        assertEquals("servers", e.params().get(0).key());
        assertEquals("chat-ui.servers", e.params().get(0).jvmProp(),
                "matches quarkus-chat-ui's own property name, since the left panel embeds its markup verbatim");
        assertEquals("port", e.params().get(1).key(), "the publish-to-parent switch is gone (RemoveParentInterpreterPublication_260913_oo01)");
    }

    @Test void audit_trail_is_two_tiles_with_companion_jars() {
        ToolRegistryEntry local = byName(ToolRegistryLoader.load(), "chat-ui-with-audit-trail-local-llm");
        ToolRegistryEntry cloud = byName(ToolRegistryLoader.load(), "chat-ui-with-audit-trail-cloud-llm");
        assertEquals("chat-ui-with-audit-trail.jar", local.jarFileName());
        assertEquals("chat-ui-with-audit-trail.jar", cloud.jarFileName(), "both tiles run the same body");
        assertEquals(List.of("chat-ui-with-audit-trail-plugin-web-tools.jar"), local.companionJars());
        assertEquals(List.of("chat-ui-with-audit-trail-plugin-web-tools.jar",
                             "chat-ui-with-audit-trail-plugin-harness.jar"), cloud.companionJars());
        assertEquals(1, local.jvmArgs().size());
        assertTrue(local.jvmArgs().get(0).startsWith("-Dchat-ui.plugins=${HOME}/works/"), local.jvmArgs().get(0));
        assertTrue(cloud.jvmArgs().get(0).contains("plugin-harness.jar"), cloud.jvmArgs().get(0));
        assertEquals(28030, local.defaultPort());
        assertEquals(28031, cloud.defaultPort());
        assertEquals(local.params().size(), cloud.params().size(), "the form is the same on both tiles");
    }

    @Test void companion_jars_default_to_empty() {
        assertTrue(byName(ToolRegistryLoader.load(), "html-saurus").companionJars().isEmpty());
    }
}
