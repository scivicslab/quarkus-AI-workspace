package com.scivicslab.aiworkspace.dashboard;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.scivicslab.aiworkspace.config.ToolRegistryEntry;

/**
 * Which catalog entries the user shows on the Dashboard tab, in what order and under what heading
 * (DashboardBuilder_261118_oo01). Held by the actor {@code dashboard}, which reads its file when the portal starts and
 * writes it on every save; nothing else reads or writes that file (EachActorRecordsItsOwnState_261118_oo01).
 *
 * <p>The catalog is not held here. It is a value read once from the jar, and each call is given it, so a newer catalog
 * applies to the user's tiles without editing the file.</p>
 */
public final class Dashboard {

    private static final Logger LOG = Logger.getLogger(Dashboard.class.getName());

    private final Path file;
    /** The tiles of the user's file, in its order; empty while the user has never saved. */
    private Optional<List<TileSpec>> saved;

    Dashboard(Path file, Optional<List<TileSpec>> saved) {
        this.file = file;
        this.saved = saved;
    }

    /**
     * Reads the user's dashboard file. A file that cannot be read is left as it is, logged, and treated as not there
     * until the user saves.
     */
    public static Dashboard open(Path file) {
        try {
            return new Dashboard(file, DashboardFile.read(file));
        } catch (IOException e) {
            LOG.warning("The dashboard file " + file + " could not be read (" + e.getMessage()
                    + "); every catalog entry is shown until the Catalog tab saves a new one");
            return new Dashboard(file, Optional.empty());
        }
    }

    /**
     * Splits the catalog's launchable entries by the user's file.
     *
     * <p>Before the user's first save, every launchable entry is shown, in catalog order. After it, the file's tiles
     * are shown in its order; entries the catalog gained since are hidden until the user shows them; tiles the
     * catalog no longer has are listed as missing and are never dropped from the file here.</p>
     */
    public Layout layout(List<ToolRegistryEntry> catalog) {
        Map<String, ToolRegistryEntry> launchable = new LinkedHashMap<>();
        for (ToolRegistryEntry e : catalog) if (!e.library()) launchable.put(e.name(), e);
        if (saved.isEmpty()) {
            return new Layout(launchable.values().stream().map(e -> tile(e, "")).toList(), List.of(), List.of());
        }
        List<Layout.Tile> shown = new ArrayList<>();
        List<TileSpec> missing = new ArrayList<>();
        for (TileSpec spec : saved.get()) {
            ToolRegistryEntry e = launchable.get(spec.name());
            if (e == null) missing.add(spec);
            else shown.add(tile(e, spec.titleOrEmpty()));
        }
        Set<String> listed = saved.get().stream().map(TileSpec::name).collect(Collectors.toSet());
        List<Layout.Tile> hidden = launchable.values().stream()
                .filter(e -> !listed.contains(e.name())).map(e -> tile(e, "")).toList();
        return new Layout(List.copyOf(shown), hidden, List.copyOf(missing));
    }

    /**
     * Replaces the user's tiles with {@code tiles} and writes the file.
     *
     * <p>A name the catalog does not have is kept only when the file already listed it: the Catalog tab shows such a
     * tile as missing and sends it back unless the user removed it. Any other unknown name is refused. The file is
     * written before the tiles held here change, so a failed write leaves both as they were.</p>
     *
     * @throws IllegalArgumentException naming the unknown entries; nothing is written
     * @throws IOException              when the file cannot be written; nothing changes
     */
    public void save(List<TileSpec> tiles, List<ToolRegistryEntry> catalog) throws IOException {
        Set<String> known = catalog.stream().filter(e -> !e.library()).map(ToolRegistryEntry::name)
                .collect(Collectors.toSet());
        Set<String> before = saved.map(l -> l.stream().map(TileSpec::name).collect(Collectors.toSet())).orElse(Set.of());
        List<String> unknown = tiles.stream().map(TileSpec::name)
                .filter(n -> !known.contains(n) && !before.contains(n)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("not in the catalog: " + String.join(", ", unknown));
        }
        List<TileSpec> copy = List.copyOf(tiles);
        DashboardFile.write(file, copy);
        saved = Optional.of(copy);
    }

    /** @return whether the user has saved a dashboard file */
    public boolean saved() {
        return saved.isPresent();
    }

    private static Layout.Tile tile(ToolRegistryEntry e, String title) {
        return new Layout.Tile(e.name(), e.defaultTitle(), title,
                e.branch() == null ? "" : e.branch(), e.jarFileName() == null ? "" : e.jarFileName(), e.link());
    }
}
