package com.scivicslab.aiworkspace.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

    @Test void all_six_entries_load() {
        List<ToolRegistryEntry> all = ToolRegistryLoader.load();
        assertEquals(6, all.size(), "expected all registry entries to parse");
    }

    @Test void chat_ui_full_form() {
        ToolRegistryEntry e = byName(ToolRegistryLoader.load(), "quarkus-chat-ui");
        assertEquals("quarkus-chat-ui.jar", e.jarFileName());
        assertEquals("scivicslab/quarkus-chat-ui", e.githubRepo());
        assertEquals(28100, e.defaultPort());
        assertEquals(5, e.params().size(), "chat-ui has 5 form fields");
        AiWorkspaceConfig.ParamDefinition provider = e.params().get(1);
        assertEquals("provider", provider.key());
        assertEquals("select", provider.type());
        assertEquals("chat-ui.provider", provider.jvmProp());
        assertEquals(3, provider.options().size(), "provider select has 3 options");
        assertEquals("claude", provider.options().get(0).value());
        assertTrue(e.params().get(0).workingDir(), "workdir sets the working directory");
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

    @Test void library_entry_flagged() {
        ToolRegistryEntry e = byName(ToolRegistryLoader.load(), "Turing-workflow-plugins");
        assertTrue(e.library());
        assertNull(e.jarFileName());
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
}
