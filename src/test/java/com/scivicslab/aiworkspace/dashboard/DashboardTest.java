package com.scivicslab.aiworkspace.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.scivicslab.aiworkspace.config.ToolRegistryEntry;

/**
 * The user's dashboard file chooses, orders and titles catalog entries by name (DashboardBuilder_261118_oo01): the
 * first start, entries the catalog gains after a save, entries it loses, and the file itself.
 */
class DashboardTest {

    @TempDir
    Path dir;

    private static ToolRegistryEntry entry(String name, String title, String branch, boolean library) {
        return new ToolRegistryEntry(name, library ? null : name + ".jar", "o/" + name, library, 0, false, false, false,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, branch, title, null, null, null);
    }

    private static final List<ToolRegistryEntry> CATALOG = List.of(
            entry("html-saurus", null, null, false),
            entry("chat-cloud", "chat-ui (cloud LLM)", null, false),
            entry("turing-workflow", null, null, true),
            entry("chat-cloud-v5x", null, "v5.x", false));

    private static List<String> names(List<Layout.Tile> tiles) {
        return tiles.stream().map(Layout.Tile::name).toList();
    }

    @Test
    void beforeTheFirstSave_everyLaunchableEntryIsShownInCatalogOrder_andNoFileIsWritten() {
        Path file = dir.resolve("dashboard.yaml");
        Dashboard d = Dashboard.open(file);
        Layout l = d.layout(CATALOG);
        assertEquals(List.of("html-saurus", "chat-cloud", "chat-cloud-v5x"), names(l.shown()), "the library entry is no tile");
        assertEquals(List.of(), l.hidden());
        assertEquals("chat-ui (cloud LLM)", l.shown().get(1).heading(), "the catalog's title");
        assertEquals("html-saurus", l.shown().get(0).heading(), "no title: the name");
        assertEquals("v5.x", l.shown().get(2).branch());
        assertFalse(Files.exists(file));
        assertFalse(d.saved());
    }

    @Test
    void aSavedDashboardShowsItsTilesInItsOrderUnderItsHeadings_andSurvivesARestart() throws Exception {
        Path file = dir.resolve("conf/dashboard.yaml");
        Dashboard d = Dashboard.open(file);
        d.save(List.of(new TileSpec("chat-cloud-v5x", "chat-ui (v5.x trial)"), new TileSpec("html-saurus", " ")), CATALOG);
        Layout l = Dashboard.open(file).layout(CATALOG);
        assertEquals(List.of("chat-cloud-v5x", "html-saurus"), names(l.shown()));
        assertEquals("chat-ui (v5.x trial)", l.shown().get(0).heading());
        assertEquals("html-saurus", l.shown().get(1).heading(), "a blank title falls back to the catalog's");
        assertEquals(List.of("chat-cloud"), names(l.hidden()));
        assertTrue(Files.readString(file).contains("chat-ui (v5.x trial)"));
    }

    @Test
    void anEntryTheCatalogGainsAfterASave_isNotShown_butListedAsHidden() throws Exception {
        Dashboard d = Dashboard.open(dir.resolve("dashboard.yaml"));
        d.save(List.of(new TileSpec("html-saurus", null)), CATALOG.subList(0, 1));
        Layout l = d.layout(CATALOG);
        assertEquals(List.of("html-saurus"), names(l.shown()));
        assertEquals(List.of("chat-cloud", "chat-cloud-v5x"), names(l.hidden()), "catalog order");
    }

    @Test
    void aTileWhoseEntryLeftTheCatalog_isSkipped_listedAsMissing_andKeptByTheNextSave() throws Exception {
        Path file = dir.resolve("dashboard.yaml");
        Files.writeString(file, """
                tiles:
                  - name: retired-tool
                    title: "Old one"
                  - name: html-saurus
                """);
        Dashboard d = Dashboard.open(file);
        Layout l = d.layout(CATALOG);
        assertEquals(List.of("html-saurus"), names(l.shown()));
        assertEquals(List.of(new TileSpec("retired-tool", "Old one")), l.missing());
        d.save(List.of(new TileSpec("html-saurus", null), new TileSpec("retired-tool", "Old one")), CATALOG);
        assertTrue(Files.readString(file).contains("retired-tool"), "never dropped by AI-workspace itself");
        d.save(List.of(new TileSpec("html-saurus", null)), CATALOG);
        assertFalse(Files.readString(file).contains("retired-tool"), "dropped when the user removes it");
    }

    @Test
    void aNewNameTheCatalogDoesNotHave_isRefused_andNothingIsWritten() {
        Path file = dir.resolve("dashboard.yaml");
        Dashboard d = Dashboard.open(file);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> d.save(List.of(new TileSpec("no-such-tool", null)), CATALOG));
        assertTrue(e.getMessage().contains("no-such-tool"));
        assertFalse(Files.exists(file));
        assertFalse(d.saved());
    }

    @Test
    void aLibraryEntryCannotBeShown() {
        Dashboard d = Dashboard.open(dir.resolve("dashboard.yaml"));
        assertThrows(IllegalArgumentException.class, () -> d.save(List.of(new TileSpec("turing-workflow", null)), CATALOG));
    }

    @Test
    void aFileThatIsNotADashboardFile_isLeftAlone_andEveryEntryIsShown() throws Exception {
        Path file = dir.resolve("dashboard.yaml");
        Files.writeString(file, "tiles: [ {title: no name} ]\n");
        Layout l = Dashboard.open(file).layout(CATALOG);
        assertEquals(3, l.shown().size());
        assertEquals("tiles: [ {title: no name} ]\n", Files.readString(file));
    }

    @Test
    void theFileRoundTrips() throws Exception {
        Path file = dir.resolve("a/b/dashboard.yaml");
        DashboardFile.write(file, List.of(new TileSpec("x", "Title: with colon"), new TileSpec("y", null)));
        assertEquals(Optional.of(List.of(new TileSpec("x", "Title: with colon"), new TileSpec("y", null))),
                DashboardFile.read(file));
        assertEquals(Optional.empty(), DashboardFile.read(dir.resolve("none.yaml")));
    }
}
