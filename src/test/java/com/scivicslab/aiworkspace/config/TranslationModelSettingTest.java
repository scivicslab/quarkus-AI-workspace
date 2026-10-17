package com.scivicslab.aiworkspace.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TranslationModelSettingTest {

    @TempDir
    Path dir;

    @Test
    void unsetIsBlank() {
        assertEquals("", new TranslationModelSetting(dir.resolve("config/s.properties")).get());
    }

    @Test
    void savedValueIsReadBackByAnotherInstance() throws Exception {
        Path file = dir.resolve("config/s.properties");
        new TranslationModelSetting(file).set("google/gemma-4-26B-A4B-it");
        assertEquals("google/gemma-4-26B-A4B-it", new TranslationModelSetting(file).get());
    }

    @Test
    void blankClearsTheChoice() throws Exception {
        TranslationModelSetting s = new TranslationModelSetting(dir.resolve("s.properties"));
        s.set("GLM-5.3-Flash-EXL3");
        s.set("  ");
        assertEquals("", s.get());
    }
}
