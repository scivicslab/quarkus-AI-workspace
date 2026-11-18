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

    @Test void html_saurus_args_and_argpos() {
        ToolRegistryEntry e = byName(ToolRegistryLoader.load(), "html-saurus");
        List<String> args = List.of("serve", "--dir", "${HOME}/works", "--port", "${PORT}");
        assertEquals(args, e.args());
        assertEquals(2, e.params().size(), "html-saurus has a Document Root selector and a port field");

        // argPos replaces args[argPos] with what the Document Root field holds. html-saurus split
        // into one jar per mode, so the first word is the command the jar has rather than the
        // directory: at 0 the directory would be written over "serve" and the portal would print
        // its command list instead of starting.
        int dirPos = e.params().get(0).argPos();
        assertEquals("${HOME}/works", args.get(dirPos),
                "Document Root must replace the directory, not the command or an option name");
        assertEquals("port", e.params().get(1).key());
    }

    @Test void turing_editor_jvmargs() {
        ToolRegistryEntry e = byName(ToolRegistryLoader.load(), "turing-workflow-editor");
        assertEquals(List.of("-Xmx2g", "-XX:G1PeriodicGCInterval=60000"), e.jvmArgs());
        assertEquals(28120, e.defaultPort());
    }

    // Without a heap cap the JVM grows to a quarter of RAM, and without the periodic GC it keeps
    // that heap after the work is done; every launchable tile runs a JVM, so every one carries both
    // flags. A link entry runs nothing here (DashboardBuilder_261118_oo01).
    @Test void every_tile_caps_heap_and_returns_it() {
        for (ToolRegistryEntry e : ToolRegistryLoader.load()) {
            if (e.library() || e.link()) continue;
            assertTrue(e.jvmArgs().stream().anyMatch(a -> a.startsWith("-Xmx")),
                    e.name() + " must cap its heap");
            assertTrue(e.jvmArgs().contains("-XX:G1PeriodicGCInterval=60000"),
                    e.name() + " must return unused heap to the OS");
        }
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

    @Test void the_gpu_broker_is_watched_from_the_catalog_but_never_started() throws IOException {
        // One broker stands in front of the cluster's GPU nodes and runs inside the cluster. A tile
        // that launches one would let anyone start a second here, dividing the same GPUs between two
        // schedulers that cannot see each other's queues. Every launchable tile comes from the
        // registry, so no launchable entry may name a broker under any name, jar or repository. The
        // broker is a link entry: it is shown and opened, and has nothing to launch or build
        // (DashboardBuilder_261118_oo01).
        for (ToolRegistryEntry e : ToolRegistryLoader.load()) {
            if (e.link()) continue;
            String said = (e.name() + " " + e.jarFileName() + " " + e.githubRepo()).toLowerCase();
            assertFalse(said.contains("gpu-broker"), "the registry must not offer a GPU broker to launch: " + e.name());
        }
        ToolRegistryEntry broker = byName(ToolRegistryLoader.load(), "gpu-broker");
        assertTrue(broker.link(), "the broker is a link entry");
        assertNull(broker.jarFileName());
        assertNull(broker.githubRepo());
        // A link entry's tile only reads what it shows: no launch form, no action list, no Run, and
        // nothing that builds, downloads or restarts.
        String tile = brokerTileOf(templateText("/templates/dashboard.html"));
        assertTrue(tile.contains("data-view-only=\"true\""), "the broker tile is marked view-only");
        assertTrue(tile.contains("target=\"_blank\""), "the broker tile opens the broker's own page in a new tab");
        for (String control : List.of("launchTool(", "toggleLaunchForm(", "runAction(", "class=\"tool-action\"",
                "btn-run-", "launch-form", "build-snapshot", "restart-all", "/download")) {
            assertFalse(tile.contains(control), "the broker tile must not carry " + control);
        }
    }

    /** The markup of a link entry's tile, the GPU broker's among them: the {@code tool.link} branch of the tile loop. */
    private static String brokerTileOf(String dashboard) {
        int start = dashboard.indexOf("{#if tool.link}");
        assertTrue(start >= 0, "the Dashboard tab draws link entries");
        int end = dashboard.indexOf("{! data-github marks the tiles", start);
        assertTrue(end > start, "the link tile ends where the launchable tile begins");
        return dashboard.substring(start, end);
    }

    private static String templateText(String resource) throws IOException {
        try (InputStream in = ToolRegistryLoaderTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, resource + " is on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
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

    /** quarkus-chat-ui (2.x and its 3.x line) and quarkus-chat-ui3 were the prototypes of chat-ui-with-audit-trail. */
    @Test void quarkus_chat_ui_prototypes_are_retired() {
        List<String> names = ToolRegistryLoader.load().stream().map(ToolRegistryEntry::name).toList();
        for (String retired : List.of("quarkus-chat-ui", "quarkus-chat-ui-3", "quarkus-chat-ui3")) {
            assertFalse(names.contains(retired), retired + " is retired; chat-ui-with-audit-trail is the final form");
        }
        assertTrue(ToolRegistryLoader.load().stream()
                        .noneMatch(e -> "scivicslab/quarkus-chat-ui".equals(e.githubRepo())
                                || "scivicslab/quarkus-chat-ui3".equals(e.githubRepo())),
                "no tile builds or downloads from the retired repositories");
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
                "matches the property name of quarkus-chat-ui, its prototype, since the left panel embeds its markup verbatim");
        assertEquals("port", e.params().get(1).key(), "the publish-to-parent switch is gone (RemoveParentInterpreterPublication_260913_oo01)");
    }

    @Test void audit_trail_is_three_tiles_one_per_start_up_configuration() {
        ToolRegistryEntry sealed = byName(ToolRegistryLoader.load(), "chat-ui-with-audit-trail-no-web-tools");
        assertEquals("chat-ui-with-audit-trail.jar", sealed.jarFileName(), "the same body as the other two");
        assertEquals(28032, sealed.defaultPort());
        assertTrue(sealed.companionJars().isEmpty(),
                "no plugin jar is what makes this the configuration with no way out to the web,"
                        + " and no shell plugin either");
        assertTrue(sealed.jvmArgs().stream().noneMatch(a -> a.startsWith("-Dchat-ui.plugins=")),
                "it passes no -Dchat-ui.plugins, which is how an adopted process is credited to it");
        assertEquals(List.of("-Xmx2g", "-XX:G1PeriodicGCInterval=60000"), sealed.jvmArgs(),
                "only the heap flags every audit-trail tile shares");
        ToolRegistryEntry local = byName(ToolRegistryLoader.load(), "chat-ui-with-audit-trail-local-llm");
        assertEquals(local.params().size(), sealed.params().size(), "the form is the same on all three tiles");
    }

    @Test void audit_trail_is_two_tiles_with_companion_jars() {
        ToolRegistryEntry local = byName(ToolRegistryLoader.load(), "chat-ui-with-audit-trail-local-llm");
        ToolRegistryEntry cloud = byName(ToolRegistryLoader.load(), "chat-ui-with-audit-trail-cloud-llm");
        assertEquals("chat-ui-with-audit-trail.jar", local.jarFileName());
        assertEquals("chat-ui-with-audit-trail.jar", cloud.jarFileName(), "both tiles run the same body");
        assertEquals(List.of("chat-ui-with-audit-trail-plugin-web-tools.jar",
                             "chat-ui-with-audit-trail-plugin-shell.jar"), local.companionJars());
        assertEquals(List.of("chat-ui-with-audit-trail-plugin-web-tools.jar",
                             "chat-ui-with-audit-trail-plugin-shell.jar",
                             "chat-ui-with-audit-trail-plugin-harness.jar"), cloud.companionJars(),
                "bash comes with the shell plugin, so the two web-reaching tiles carry it");
        List<String> heap = List.of("-Xmx2g", "-XX:G1PeriodicGCInterval=60000");
        assertEquals(heap, local.jvmArgs().subList(0, 2), "the same heap flags lead every audit-trail tile");
        assertEquals(heap, cloud.jvmArgs().subList(0, 2), "the same heap flags lead every audit-trail tile");
        assertEquals(3, local.jvmArgs().size());
        assertTrue(local.jvmArgs().get(2).startsWith("-Dchat-ui.plugins=${HOME}/works/"), local.jvmArgs().get(2));
        assertTrue(cloud.jvmArgs().get(2).contains("plugin-harness.jar"), cloud.jvmArgs().get(2));
        assertEquals(28030, local.defaultPort());
        assertEquals(28031, cloud.defaultPort());
        assertEquals(local.params().size(), cloud.params().size(), "the form is the same on both tiles");
    }

    @Test void companion_jars_default_to_empty() {
        assertTrue(byName(ToolRegistryLoader.load(), "html-saurus").companionJars().isEmpty());
    }
}
