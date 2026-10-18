package com.scivicslab.aiworkspace.backend.jvm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class InstanceRecordsTest {

    @TempDir
    Path dir;

    @Test
    void writtenParametersAreReadBack() throws Exception {
        Map<String, String> params = Map.of("dir", "/home/me/my docs", "title", "A=B: 日本語", "port", "");
        InstanceRecords.write(dir, "html-saurus", 28020, 1_700_000_000_000L, params);

        InstanceRecords.Record r = InstanceRecords.read(dir, "html-saurus", 28020).orElseThrow();

        assertEquals(1_700_000_000_000L, r.launchedAtMs());
        assertEquals(params, r.params());
    }

    @Test
    void anInstanceWithNoParametersIsStillRecorded() throws Exception {
        InstanceRecords.write(dir, "html-saurus", 28001, 5L, Map.of());
        assertEquals(Map.of(), InstanceRecords.read(dir, "html-saurus", 28001).orElseThrow().params());
    }

    @Test
    void noFileMeansNoRecord() {
        assertEquals(Optional.empty(), InstanceRecords.read(dir, "html-saurus", 28099));
    }

    @Test
    void anUnreadableFileMeansNoRecord() throws Exception {
        Files.writeString(dir.resolve("html-saurus-28005.properties"), "launchedAtMs=not-a-number\n");
        assertEquals(Optional.empty(), InstanceRecords.read(dir, "html-saurus", 28005));
    }

    @Test
    void deleteRemovesTheRecordAndToleratesNone() throws Exception {
        InstanceRecords.write(dir, "html-saurus", 28020, 1L, Map.of("a", "b"));
        InstanceRecords.delete(dir, "html-saurus", 28020);
        assertEquals(Optional.empty(), InstanceRecords.read(dir, "html-saurus", 28020));
        assertDoesNotThrow(() -> InstanceRecords.delete(dir, "html-saurus", 28020));
    }

    @Test
    void tools_and_ports_do_not_share_a_record() throws Exception {
        InstanceRecords.write(dir, "html-saurus", 28020, 1L, Map.of("dir", "one"));
        InstanceRecords.write(dir, "html-saurus", 28021, 1L, Map.of("dir", "two"));
        InstanceRecords.write(dir, "quarkus-chat-ui", 28020, 1L, Map.of("dir", "three"));
        assertEquals("one", InstanceRecords.read(dir, "html-saurus", 28020).orElseThrow().params().get("dir"));
        assertEquals("two", InstanceRecords.read(dir, "html-saurus", 28021).orElseThrow().params().get("dir"));
        assertEquals("three", InstanceRecords.read(dir, "quarkus-chat-ui", 28020).orElseThrow().params().get("dir"));
    }

    @Test
    void aRecordBelongsToAProcessThatStartedJustAfterTheLaunch() {
        InstanceRecords.Record r = new InstanceRecords.Record(1_000_000L, Map.of());
        assertTrue(InstanceRecords.belongsTo(r, Instant.ofEpochMilli(1_000_000L + 800)));
        assertTrue(InstanceRecords.belongsTo(r, Instant.ofEpochMilli(1_000_000L + 60_000)));
    }

    @Test
    void aRecordDoesNotBelongToAProcessFromAnotherTime() {
        InstanceRecords.Record r = new InstanceRecords.Record(1_000_000L, Map.of());
        assertFalse(InstanceRecords.belongsTo(r, Instant.ofEpochMilli(1_000_000L - 3_600_000)));
        assertFalse(InstanceRecords.belongsTo(r, Instant.ofEpochMilli(1_000_000L + 3_600_000)));
        assertFalse(InstanceRecords.belongsTo(r, null));
    }
}
