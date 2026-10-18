package com.scivicslab.aiworkspace.backend.jvm;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;

/**
 * What an instance was launched with, kept in a file per instance so that a portal restarted
 * later can start the instance again the way it was.
 *
 * <p>A process this portal adopts after a restart outlived the portal that launched it, and the
 * operating system does not hand back the parameters it was given (Document Root, title, provider,
 * working directory). The launch writes them here; {@code ProcessSupervisor.adopt} reads them back.
 * A file is removed when the instance is stopped from here.</p>
 *
 * <p>A file is trusted only for a process that started shortly after the launch it records
 * ({@link #belongsTo}): a leftover from an earlier instance on the same port, or a process started
 * by hand there, must not lend its parameters to a different one.</p>
 */
final class InstanceRecords {

    static final Path DEFAULT_DIR = Path.of(System.getProperty("user.home"),
            ".local/share/quarkus-ai-workspace/instances");

    /** How long before and after the recorded launch the process's own start may fall. */
    private static final long START_BEFORE_MS = 5_000;
    private static final long START_AFTER_MS = 120_000;

    private static final String LAUNCHED_AT = "launchedAtMs";
    private static final String PARAM_PREFIX = "param.";

    /** What one file holds. */
    record Record(long launchedAtMs, Map<String, String> params) {}

    private InstanceRecords() {}

    private static Path fileFor(Path dir, String toolName, int port) {
        return dir.resolve(toolName + "-" + port + ".properties");
    }

    static void write(Path dir, String toolName, int port, long launchedAtMs, Map<String, String> params)
            throws IOException {
        Properties props = new Properties();
        props.setProperty(LAUNCHED_AT, Long.toString(launchedAtMs));
        params.forEach((k, v) -> props.setProperty(PARAM_PREFIX + k, v == null ? "" : v));
        Files.createDirectories(dir);
        try (Writer out = Files.newBufferedWriter(fileFor(dir, toolName, port), StandardCharsets.UTF_8)) {
            props.store(out, "Launch parameters of " + toolName + " on port " + port);
        }
    }

    /** @return the record for this tool and port, or empty when there is none or it cannot be read */
    static Optional<Record> read(Path dir, String toolName, int port) {
        Path file = fileFor(dir, toolName, port);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Properties props = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(in);
            long launchedAt = Long.parseLong(props.getProperty(LAUNCHED_AT, ""));
            Map<String, String> params = new TreeMap<>();
            for (String name : props.stringPropertyNames()) {
                if (name.startsWith(PARAM_PREFIX)) {
                    params.put(name.substring(PARAM_PREFIX.length()), props.getProperty(name));
                }
            }
            return Optional.of(new Record(launchedAt, Collections.unmodifiableMap(params)));
        } catch (IOException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    static void delete(Path dir, String toolName, int port) {
        try {
            Files.deleteIfExists(fileFor(dir, toolName, port));
        } catch (IOException e) {
            // a record left behind is ignored by belongsTo once its process is a different one
        }
    }

    /** Whether a process that started at {@code processStart} is the one this record was written for. */
    static boolean belongsTo(Record record, Instant processStart) {
        if (processStart == null) {
            return false;
        }
        long delta = processStart.toEpochMilli() - record.launchedAtMs();
        return delta >= -START_BEFORE_MS && delta <= START_AFTER_MS;
    }
}
