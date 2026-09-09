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
     * One recorded row: a single request/response the conversation wrote.
     *
     * @param logId   the row in the {@code logs} table
     * @param when    when it was recorded
     * @param label   the row's label, e.g. {@code turn7/step1/llm}
     * @param message the whole text
     */
    public record Row(long logId, String when, String label, String message) {}

    /**
     * What is shown when a search result is opened, and where its arrows lead.
     *
     * <p>In turn mode {@link #rows} holds every row of one turn; in row mode it holds the one row.
     * {@link #previousId} and {@link #nextId} are the row to open next, already resolved for the
     * mode that was asked for, or {@code 0} at either end of the conversation.</p>
     *
     * @param found        whether there is such a row at all
     * @param logId        the row this view is anchored on
     * @param sessionId    the conversation it belongs to
     * @param conversation the conversation's recorded name
     * @param program      which program and port held the conversation
     * @param turn         the turn key, e.g. {@code turn7}
     * @param rows         what to show, oldest first
     * @param previousId   the row the back arrow opens, or {@code 0}
     * @param previousLabel what that row is, for the arrow to name
     * @param nextId       the row the forward arrow opens, or {@code 0}
     * @param nextLabel    what that row is, for the arrow to name
     */
    public record View(boolean found, long logId, long sessionId, String conversation,
                       String program, String turn, List<Row> rows,
                       long previousId, String previousLabel,
                       long nextId, String nextLabel) {}

    /** An answer for a row that is not in the database. */
    private static final View NOT_FOUND =
            new View(false, 0, 0, "", "", "", List.of(), 0, "", 0, "");

    /**
     * The turn that {@code logId} belongs to, with the arrows pointing at the turns either side.
     *
     * <p>The turn is the unit a person reads: one exchange, which the log records as several rows
     * (the model call, the tools it ran, the call after them). {@link #rowView} steps through those
     * rows one at a time instead.</p>
     *
     * <p>The neighbouring turns are found from this turn's first and last row, not from its number:
     * turn numbering has gaps in the recorded data (a conversation runs turn6 then turn8), so
     * counting would walk off the end of what was written.</p>
     */
    public View turnView(long logId) {
        return view(logId, true);
    }

    /** The one row {@code logId} names, with the arrows pointing at the rows either side. */
    public View rowView(long logId) {
        return view(logId, false);
    }

    private View view(long logId, boolean wholeTurn) {
        if (!present()) {
            return NOT_FOUND;
        }
        try (Connection c = open()) {
            Anchor anchor = anchor(c, logId);
            if (anchor == null) {
                return NOT_FOUND;
            }
            List<Row> rows = wholeTurn ? turnRows(c, anchor) : List.of(row(c, logId));
            if (rows.isEmpty() || rows.get(0) == null) {
                return NOT_FOUND;
            }
            long firstId = rows.get(0).logId();
            long lastId = rows.get(rows.size() - 1).logId();
            Neighbour previous = neighbour(c, anchor.sessionId(), firstId, false);
            Neighbour next = neighbour(c, anchor.sessionId(), lastId, true);
            return new View(true, logId, anchor.sessionId(), anchor.conversation(),
                    anchor.program(), anchor.turn(), rows,
                    previous.logId(), previous.label(), next.logId(), next.label());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not read around turn " + logId, e);
            return NOT_FOUND;
        }
    }

    /** Which conversation and turn a row belongs to. */
    private record Anchor(long sessionId, String conversation, String program, String turn) {}

    /** A row an arrow leads to; {@code logId} is {@code 0} when there is none. */
    private record Neighbour(long logId, String label) {}

    private Anchor anchor(Connection c, long logId) throws Exception {
        String sql = """
                SELECT l.session_id, l.label, s.workflow_name, s.command_line, s.source_db
                FROM logs l JOIN sessions s ON s.id = l.session_id
                WHERE l.id = ?
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, logId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new Anchor(rs.getLong("session_id"), nz(rs.getString("workflow_name")),
                        programOf(rs.getString("command_line"), rs.getString("source_db")),
                        turnKey(nz(rs.getString("label"))));
            }
        }
    }

    /** Every row of the anchor's turn, oldest first. */
    private List<Row> turnRows(Connection c, Anchor anchor) throws Exception {
        String sql = """
                SELECT id, timestamp, label, message FROM logs
                WHERE session_id = ? AND label LIKE ?
                ORDER BY id
                """;
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, anchor.sessionId());
            // The turn key plus "/" so that turn1 does not also take turn10's rows.
            ps.setString(2, anchor.turn() + "/%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Row(rs.getLong("id"), text(rs.getTimestamp("timestamp")),
                            nz(rs.getString("label")), nz(rs.getString("message"))));
                }
            }
        }
        return rows;
    }

    private Row row(Connection c, long logId) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, timestamp, label, message FROM logs WHERE id = ?")) {
            ps.setLong(1, logId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Row(rs.getLong("id"), text(rs.getTimestamp("timestamp")),
                        nz(rs.getString("label")), nz(rs.getString("message"))) : null;
            }
        }
    }

    /**
     * The row just past {@code fromId} in the same conversation, in either direction.
     *
     * <p>Ordering on {@code id} rather than on {@code timestamp}: {@code LogMerger} copies one
     * conversation's rows in one contiguous run, so within a conversation the merged {@code id}
     * order is the order the rows were written — checked against every row of the merged database,
     * where none is older than the row before it.</p>
     */
    private Neighbour neighbour(Connection c, long sessionId, long fromId, boolean forward)
            throws Exception {
        String sql = forward
                ? "SELECT id, label FROM logs WHERE session_id = ? AND id > ? ORDER BY id LIMIT 1"
                : "SELECT id, label FROM logs WHERE session_id = ? AND id < ? ORDER BY id DESC LIMIT 1";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, sessionId);
            ps.setLong(2, fromId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Neighbour(rs.getLong("id"), nz(rs.getString("label")))
                        : new Neighbour(0, "");
            }
        }
    }

    /**
     * The turn a label belongs to.
     *
     * @param label a row's label, e.g. {@code turn7/step1/llm}
     * @return the part before the first {@code /}, e.g. {@code turn7}; the whole label when it
     *         holds no {@code /}, so that a row written in some other shape still groups with
     *         itself rather than with everything else
     */
    static String turnKey(String label) {
        int slash = label.indexOf('/');
        return slash < 0 ? label : label.substring(0, slash);
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
