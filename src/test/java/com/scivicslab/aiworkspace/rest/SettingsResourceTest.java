package com.scivicslab.aiworkspace.rest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SettingsResourceTest {

    @Test
    void modelIdsAreTakenFromEachEntry() {
        String json = "{\"object\":\"list\",\"data\":[{\"id\":\"GLM-5.3-Flash-EXL3\",\"object\":\"model\"},"
                + "{\"id\":\"google/gemma-4-26B-A4B-it\",\"object\":\"model\"}]}";
        assertEquals(List.of("GLM-5.3-Flash-EXL3", "google/gemma-4-26B-A4B-it"), SettingsResource.modelIds(json));
    }

    @Test
    void noEntriesGiveAnEmptyList() {
        assertEquals(List.of(), SettingsResource.modelIds("{\"data\":[]}"));
    }
}
