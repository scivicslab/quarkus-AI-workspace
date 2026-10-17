package com.scivicslab.aiworkspace.config;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * The model html-saurus translates with, chosen on the Settings screen.
 *
 * <p>Kept in its own file, {@code ${user.dir}/config/ai-workspace-settings.properties}, not in
 * {@code config/application.yaml}: that one is read by every Quarkus tool launched from this
 * directory, and a setting only html-saurus asks for does not belong in what they all load.</p>
 *
 * <p>Read on every call rather than held in a field, so a value saved on the Settings screen is
 * what the next question gets ({@code ServiceDirectory_260905_oo01}). A blank value means "not
 * chosen": the asker keeps its own default.</p>
 */
@ApplicationScoped
public class TranslationModelSetting {

    static final String KEY = "translation.model";

    private final Path file;

    public TranslationModelSetting() {
        this(Path.of(System.getProperty("user.dir"), "config", "ai-workspace-settings.properties"));
    }

    TranslationModelSetting(Path file) {
        this.file = file;
    }

    /** @return the chosen model id, or {@code ""} when none is chosen or the file is unreadable */
    public synchronized String get() {
        if (!Files.exists(file)) {
            return "";
        }
        Properties props = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(in);
        } catch (IOException e) {
            return "";
        }
        return props.getProperty(KEY, "").trim();
    }

    /**
     * Saves the chosen model id; a blank value clears the choice.
     *
     * @throws IOException if the file cannot be written
     */
    public synchronized void set(String model) throws IOException {
        Properties props = new Properties();
        props.setProperty(KEY, model == null ? "" : model.trim());
        Files.createDirectories(file.getParent());
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            props.store(out, "Set from the Settings screen");
        }
    }
}
