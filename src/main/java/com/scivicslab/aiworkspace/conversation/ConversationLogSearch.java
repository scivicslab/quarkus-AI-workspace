package com.scivicslab.aiworkspace.conversation;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Searches the conversations of every tool instance at once
 * ({@code ConversationLogSearchPlacement_260908_oo01}).
 *
 * <p>Each tool instance writes its conversations into its own H2 database file, named after the
 * port it listens on. {@link ConversationLogMerge} copies all of those files into one database;
 * this reads that one and never writes to it.</p>
 *
 * <p>The match is a substring of the message, not a word. Japanese text has no spaces to split on,
 * and a morphological analyser drops proper nouns its dictionary does not carry, so a substring
 * match is what finds a program name typed mid-sentence.</p>
 */
@ApplicationScoped
public class ConversationLogSearch {

    private static final Logger LOG = Logger.getLogger(ConversationLogSearch.class.getName());

    /**
     * The merged database, as a path with no {@code .mv.db} extension.
     *
     * <p>Written by {@link ConversationLogMerge} and read here. Nothing on this side creates it: a
     * search that silently created an empty database would answer "no conversations" for a question
     * it never looked for an answer to.</p>
     */
    @ConfigProperty(name = "ai-workspace.conversation-log.db-path",
            defaultValue = "${user.dir}/chat-ui-iolog-all")
    String dbPath;

    /** @return the configured path with {@code ${user.dir}} and {@code ${user.home}} filled in */
    private Path database() {
        return Path.of(com.scivicslab.aiworkspace.config.PathTemplate.expand(dbPath));
    }

    /** How much of the message is shown around the match. */
    private static final int SNIPPET_MARGIN = 140;

    /**
     * One matching turn.
     *
     * @param logId        the row in the {@code logs} table, for fetching the whole message
     * @param sessionId    the conversation this turn belongs to
     * @param when         when the turn was recorded
     * @param conversation the conversation's recorded name
     * @param program      which program and port held the conversation, as far as it can be told
     * @param label        the turn's label, e.g. {@code turn7/step1/llm}
     * @param snippet      the text around the match
     */
    public record Hit(long logId, long sessionId, String when, String conversation,
                      String program, String label, String snippet) {}

    /**
     * What one search found.
     *
     * @param hits    the matching turns, newest first
     * @param limited true when more turns matched than were returned
     * @param error   what went wrong, or {@code ""} when nothing did
     */
    public record Result(List<Hit> hits, boolean limited, String error) {}

    /**
     * What the merged database holds.
     *
     * @param present       whether the merged database exists at all
     * @param conversations how many conversations it holds
     * @param turns         how many turns it holds
     * @param newest        the most recent turn's time, or {@code ""} when there are none
     * @param error         what went wrong, or {@code ""} when nothing did
     */
    public record Overview(boolean present, long conversations, long turns, String newest,
                           String error) {}

    /** @return the merged database's path, for the screen to show */
    public String databasePath() {
        return database().toAbsolutePath().toString();
    }

    /** @return whether the merged database file exists */
    public boolean present() {
        return Files.isRegularFile(Path.of(database() + ".mv.db"));
    }

    /** @return how much the merged database holds, for the screen to show */
    public Overview overview() {
        if (!present()) {
            return new Overview(false, 0, 0, "", "");
        }
        String sql = """
                SELECT (SELECT COUNT(*) FROM sessions) AS conversations,
                       (SELECT COUNT(*) FROM logs) AS turns,
                       (SELECT MAX(timestamp) FROM logs) AS newest
                """;
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return new Overview(true, rs.getLong("conversations"), rs.getLong("turns"),
                        text(rs.getTimestamp("newest")), "");
            }
            return new Overview(true, 0, 0, "", "");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not read the merged conversation log", e);
            return new Overview(true, 0, 0, "", String.valueOf(e.getMessage()));
        }
    }

    /**
     * Finds the turns whose text contains {@code query}.
     *
     * @param query what to look for; blank finds nothing rather than everything
     * @param limit how many turns to return at most
     * @return the matching turns, newest first
     */
    public Result search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return new Result(List.of(), false, "");
        }
        if (!present()) {
            return new Result(List.of(), false,
                    "Nothing to search yet: there is no merged database at "
                            + databasePath() + ".mv.db.");
        }
        // One row over the limit, so that "there are more" is known without counting every match.
        String sql = """
                SELECT l.id, l.session_id, l.timestamp, l.label, l.message,
                       s.workflow_name, s.command_line, s.source_db
                FROM logs l JOIN sessions s ON s.id = l.session_id
                WHERE LOWER(l.message) LIKE ?
                ORDER BY l.timestamp DESC
                LIMIT ?
                """;
        List<Hit> hits = new ArrayList<>();
        boolean limited = false;
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, "%" + query.toLowerCase() + "%");
            ps.setInt(2, limit + 1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (hits.size() == limit) {
                        limited = true;
                        break;
                    }
                    hits.add(new Hit(
                            rs.getLong("id"),
                            rs.getLong("session_id"),
                            text(rs.getTimestamp("timestamp")),
                            nz(rs.getString("workflow_name")),
                            programOf(rs.getString("command_line"), rs.getString("source_db")),
                            nz(rs.getString("label")),
                            snippet(rs.getString("message"), query)));
                }
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Conversation search failed", e);
            return new Result(List.of(), false, String.valueOf(e.getMessage()));
        }
        return new Result(List.copyOf(hits), limited, "");
    }

    /**
     * Returns one turn's whole text.
     *
     * @param logId the row in the {@code logs} table
     * @return the message, or {@code ""} when there is no such row
     */
    public String message(long logId) {
        if (!present()) {
            return "";
        }
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("SELECT message FROM logs WHERE id = ?")) {
            ps.setLong(1, logId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? nz(rs.getString("message")) : "";
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not read turn " + logId, e);
            return "";
        }
    }

    /**
     * Opens the merged database for one call.
     *
     * <p>A connection per call rather than one held open: {@code log-merge} replaces this file
     * while the portal is running, and a held connection would keep answering from the file that
     * was there when the portal started.</p>
     *
     * <p>{@code AUTO_SERVER=TRUE} matches how every conversation-log database is opened elsewhere.
     * H2 refuses a second connection that does not ask for the same mode.</p>
     */
    private Connection open() throws Exception {
        return DriverManager.getConnection(
                "jdbc:h2:" + database().toAbsolutePath() + ";AUTO_SERVER=TRUE");
    }

    /**
     * Says which program and port held a conversation.
     *
     * <p>Read from the command line the writing process recorded. Conversations recorded before
     * that was written have no command line, and then the source file name is all there is: it
     * carries the port but not which of the programs that share the file-name form wrote it.</p>
     *
     * @param commandLine the writing process's command line, or null
     * @param sourceDb    the file the conversation was merged from, or null
     * @return e.g. {@code quarkus-chat-ui:28013}, or the source file name, or {@code "unknown"}
     */
    static String programOf(String commandLine, String sourceDb) {
        if (commandLine != null && !commandLine.isBlank()) {
            String jar = argumentAfter(commandLine, "-jar");
            String port = valueOf(commandLine, "-Dquarkus.http.port=");
            String name = jar == null ? null : jarName(jar);
            if (name != null && port != null) {
                return name + ":" + port;
            }
            if (name != null) {
                return name;
            }
        }
        return sourceDb == null || sourceDb.isBlank() ? "unknown" : trimDbSuffix(sourceDb);
    }

    /** @return the token following {@code flag}, or null when the flag is absent or last */
    private static String argumentAfter(String commandLine, String flag) {
        String[] parts = commandLine.split("\\s+");
        for (int i = 0; i < parts.length - 1; i++) {
            if (flag.equals(parts[i])) {
                return parts[i + 1];
            }
        }
        return null;
    }

    /** @return the text after {@code prefix} in the token that starts with it, or null */
    private static String valueOf(String commandLine, String prefix) {
        for (String part : commandLine.split("\\s+")) {
            if (part.startsWith(prefix)) {
                return part.substring(prefix.length());
            }
        }
        return null;
    }

    /**
     * @param jarPath a path ending in {@code .jar}
     * @return the file name without the extension and without a trailing version, e.g.
     *         {@code quarkus-chat-ui} for {@code /home/x/quarkus-chat-ui-2.5.0-SNAPSHOT.jar}
     */
    static String jarName(String jarPath) {
        String name = jarPath.substring(jarPath.lastIndexOf('/') + 1);
        if (name.endsWith(".jar")) {
            name = name.substring(0, name.length() - ".jar".length());
        }
        // Cut at the first segment that begins with a digit: that is where the version starts.
        int cut = -1;
        for (int i = 1; i < name.length(); i++) {
            if (name.charAt(i - 1) == '-' && Character.isDigit(name.charAt(i))) {
                cut = i - 1;
                break;
            }
        }
        return cut > 0 ? name.substring(0, cut) : name;
    }

    /** @return the file name without the {@code .mv.db} extension */
    private static String trimDbSuffix(String fileName) {
        return fileName.endsWith(".mv.db")
                ? fileName.substring(0, fileName.length() - ".mv.db".length())
                : fileName;
    }

    /**
     * Cuts the text around the first match.
     *
     * @param message the whole turn
     * @param query   what was searched for
     * @return the text around the match, with an ellipsis on each side that was cut
     */
    static String snippet(String message, String query) {
        if (message == null) {
            return "";
        }
        String flat = message.replaceAll("\\s+", " ").strip();
        int at = flat.toLowerCase().indexOf(query.toLowerCase());
        if (at < 0) {
            // The match was on the unflattened text: a query spanning a line break.
            return flat.length() <= SNIPPET_MARGIN * 2 ? flat
                    : flat.substring(0, SNIPPET_MARGIN * 2) + "…";
        }
        int from = Math.max(0, at - SNIPPET_MARGIN);
        int to = Math.min(flat.length(), at + query.length() + SNIPPET_MARGIN);
        return (from > 0 ? "…" : "") + flat.substring(from, to) + (to < flat.length() ? "…" : "");
    }

    private static String text(Timestamp t) {
        return t == null ? "" : t.toLocalDateTime().toString().replace('T', ' ');
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
