package com.scivicslab.aiworkspace.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.scivicslab.aiworkspace.dashboard.Dashboard;
import com.scivicslab.aiworkspace.dashboard.Layout;

/**
 * A link entry (kind: link) shows and opens a service that runs elsewhere and starts nothing
 * (DashboardBuilder_261118_oo01). The GPU broker is the one in the bundled catalog.
 */
class LinkEntryTest {

    private static List<ToolRegistryEntry> parse(String yaml) {
        return ToolRegistryLoader.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    private static ToolRegistryEntry bundled(String name) {
        return ToolRegistryLoader.load().stream().filter(e -> e.name().equals(name)).findFirst().orElseThrow();
    }

    @AfterEach
    void clearBrokerUrl() {
        System.clearProperty("gpu.broker.url");
    }

    @Test
    void theBundledGpuBrokerIsALinkEntry_withNothingToLaunchOrBuild() {
        ToolRegistryEntry broker = bundled("gpu-broker");
        assertTrue(broker.link());
        assertEquals("GPU broker (W206 cluster)", broker.defaultTitle());
        assertEquals("${gpu.broker.url}", broker.url(), "the address is not written a second time");
        assertEquals("gpu-broker-queues", broker.view());
        assertNull(broker.jarFileName());
        assertNull(broker.githubRepo());
        assertNull(broker.branch());
        assertEquals(List.of(), broker.jvmArgs());
        assertEquals(List.of(), broker.params());
    }

    @Test
    void theBrokerAddressIsTheOneEveryToolIsGiven() {
        ToolRegistryEntry broker = bundled("gpu-broker");
        System.setProperty("gpu.broker.url", "http://192.168.5.21:30805/");
        assertEquals(Optional.of("http://192.168.5.21:30805"), broker.resolvedUrl());
        assertEquals(GpuBrokerUrl.resolve(), broker.resolvedUrl());
    }

    @Test
    void aLiteralUrlIsUsedAsWritten() {
        ToolRegistryEntry e = parse("""
                tools:
                  - {name: wiki, kind: link, url: "https://wiki.example.org"}
                """).get(0);
        assertTrue(e.link());
        assertEquals(Optional.of("https://wiki.example.org"), e.resolvedUrl());
        assertEquals("wiki", e.defaultTitle());
    }

    @Test
    void aLinkEntryIsNeverLaunched_soAdoptionAndTheLaunchConfigNeverSeeIt() {
        assertFalse(BackendLoader.loadConfig().jvm().tools().stream().anyMatch(t -> t.name().equals("gpu-broker")),
                "not handed to JvmBackend: no process to start, stop, adopt or credit");
        assertTrue(BackendLoader.loadConfig().jvm().tools().stream().anyMatch(t -> t.name().equals("html-saurus")));
    }

    @Test
    void theCatalogTabShowsHidesAndRetitlesItLikeAnyEntry() throws Exception {
        Path file = java.nio.file.Files.createTempDirectory("link-entry").resolve("dashboard.yaml");
        Dashboard d = Dashboard.open(file);
        Layout before = d.layout(ToolRegistryLoader.load());
        Layout.Tile tile = before.shown().stream().filter(t -> t.name().equals("gpu-broker")).findFirst().orElseThrow();
        assertTrue(tile.link());
        assertEquals("", tile.jar());
        assertEquals("", tile.branch());
        d.save(List.of(new com.scivicslab.aiworkspace.dashboard.TileSpec("gpu-broker", "Broker")), ToolRegistryLoader.load());
        assertEquals("Broker", d.layout(ToolRegistryLoader.load()).shown().get(0).heading());
        d.save(List.of(new com.scivicslab.aiworkspace.dashboard.TileSpec("html-saurus", null)), ToolRegistryLoader.load());
        assertTrue(d.layout(ToolRegistryLoader.load()).hidden().stream().anyMatch(t -> t.name().equals("gpu-broker")));
    }

    @Test
    void aLinkEntryWithoutAUrl_orWithLaunchFields_isRefused() {
        List<String> noUrl = CatalogRules.violations(parse("""
                tools:
                  - {name: broker, kind: link}
                """));
        assertEquals(1, noUrl.size());
        assertTrue(noUrl.get(0).contains("needs a url"), noUrl.get(0));

        List<String> launchable = CatalogRules.violations(parse("""
                tools:
                  - {name: broker, kind: link, url: "http://x", jar: broker.jar, github: o/broker, jvmArgs: ["-Xmx1g"]}
                """));
        assertEquals(1, launchable.size());
        assertTrue(launchable.get(0).contains("jar") && launchable.get(0).contains("github")
                && launchable.get(0).contains("jvmArgs"), launchable.get(0));
    }
}
