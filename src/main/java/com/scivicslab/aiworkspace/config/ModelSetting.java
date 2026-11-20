package com.scivicslab.aiworkspace.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * One model chosen on the Settings screen for html-saurus, kept under its own key in
 * {@code ${user.dir}/config/ai-workspace-settings.properties}.
 *
 * <p>Several settings share that file, so saving one reads the file first and keeps the other keys. Read on every
 * call rather than held in a field, so a value saved on the Settings screen is what the next question gets
 * ({@code ServiceDirectory_260905_oo01}). A blank value means "not chosen": the asker keeps its own default.</p>
 */
public abstract class ModelSetting {

    private final Path file;
    private final String key;

    protected ModelSetting(Path file, String key) {
        this.file = file;
        this.key = key;
    }

    /** @return the settings file in the directory AI-workspace was started in */
    protected static Path defaultFile() {
        return Path.of(System.getProperty("user.dir"), "config", "ai-workspace-settings.properties");
    }

    /** @return the chosen model, or {@code ""} when none is chosen */
    public String get() {
        synchronized (ModelSetting.class) {
            return load().getProperty(key, "").trim();
        }
    }

    /**
     * @param model the model to choose; blank clears the choice
     * @throws IOException when the file cannot be written
     */
    public void set(String model) throws IOException {
        synchronized (ModelSetting.class) {
            Properties props = load();
            props.setProperty(key, model == null ? "" : model.trim());
            Files.createDirectories(file.getParent());
            try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                props.store(out, "Set from the Settings screen");
            }
        }
    }

    private Properties load() {
        Properties props = new Properties();
        if (!Files.exists(file)) return props;
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(in);
        } catch (IOException e) {
            // an unreadable file is the same as no choice
        }
        return props;
    }
}
