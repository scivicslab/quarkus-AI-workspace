package com.scivicslab.aiworkspace.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The revision model shares the settings file with the translation model (RevisionPanel_261120_oo01). */
class RevisionModelSettingTest {

    @TempDir
    Path dir;

    @Test
    void savingOneModelKeepsTheOther() throws Exception {
        Path file = dir.resolve("config/s.properties");
        new TranslationModelSetting(file).set("google/gemma-4-26B-A4B-it");
        new RevisionModelSetting(file).set("GLM-5.3-Flash-Uncensored");
        assertEquals("google/gemma-4-26B-A4B-it", new TranslationModelSetting(file).get());
        assertEquals("GLM-5.3-Flash-Uncensored", new RevisionModelSetting(file).get());
        new TranslationModelSetting(file).set("");
        assertEquals("GLM-5.3-Flash-Uncensored", new RevisionModelSetting(file).get());
    }

    @Test
    void unsetIsBlank() {
        assertEquals("", new RevisionModelSetting(dir.resolve("none.properties")).get());
    }
}
