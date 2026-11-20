package com.scivicslab.aiworkspace.config;

import jakarta.enterprise.context.ApplicationScoped;

import java.nio.file.Path;

/**
 * The model html-saurus translates with, chosen on the Settings screen.
 *
 * <p>Kept in its own file, {@code ${user.dir}/config/ai-workspace-settings.properties}, not in
 * {@code config/application.yaml}: that one is read by every Quarkus tool launched from this
 * directory, and a setting only html-saurus asks for does not belong in what they all load.</p>
 */
@ApplicationScoped
public class TranslationModelSetting extends ModelSetting {

    static final String KEY = "translation.model";

    public TranslationModelSetting() {
        this(defaultFile());
    }

    TranslationModelSetting(Path file) {
        super(file, KEY);
    }
}
