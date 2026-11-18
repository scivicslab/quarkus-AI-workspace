package com.scivicslab.aiworkspace.dashboard;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * The user's dashboard file, {@code ~/.config/ai-workspace/dashboard.yaml} unless the system property
 * {@value #PROPERTY} names another (DashboardBuilder_261118_oo01).
 *
 * <pre>
 * tiles:
 *   - name: chat-ui-with-audit-trail-cloud-llm
 *     title: "chat-ui (Claude Code allowed)"
 *   - name: html-saurus
 * </pre>
 */
public final class DashboardFile {

    /** The system property that names another file. */
    public static final String PROPERTY = "ai-workspace.dashboard-file";

    private DashboardFile() {}

    /** @return where the user's dashboard file is */
    public static Path location() {
        String given = System.getProperty(PROPERTY);
        if (given != null && !given.isBlank()) return Path.of(given.strip());
        return Path.of(System.getProperty("user.home"), ".config", "ai-workspace", "dashboard.yaml");
    }

    /**
     * @return the tiles the file lists, in order; empty when there is no file yet
     * @throws IOException when the file is there but cannot be read as a dashboard file
     */
    @SuppressWarnings("unchecked")
    public static Optional<List<TileSpec>> read(Path file) throws IOException {
        if (!Files.exists(file)) return Optional.empty();
        Object root;
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            root = new Yaml().load(in);
        } catch (RuntimeException e) {
            throw new IOException(file + " is not YAML: " + e.getMessage(), e);
        }
        List<TileSpec> tiles = new ArrayList<>();
        if (root instanceof Map<?, ?> map && map.get("tiles") instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m) || m.get("name") == null) {
                    throw new IOException(file + ": every element of tiles needs a name");
                }
                Object title = m.get("title");
                tiles.add(new TileSpec(m.get("name").toString(), title == null ? null : title.toString()));
            }
        } else if (root != null) {
            throw new IOException(file + ": expected a map with a tiles list");
        }
        return Optional.of(List.copyOf(tiles));
    }

    /** Writes {@code tiles} as the whole file: to a temporary file first, then renamed over the old one. */
    public static void write(Path file, List<TileSpec> tiles) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        List<Map<String, Object>> list = new ArrayList<>();
        for (TileSpec t : tiles) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.name());
            if (!t.titleOrEmpty().isEmpty()) m.put("title", t.titleOrEmpty());
            list.add(m);
        }
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Path tmp = Files.createTempFile(dir, ".dashboard", ".yaml");
        try (Writer out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            new Yaml(options).dump(Map.of("tiles", list), out);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
