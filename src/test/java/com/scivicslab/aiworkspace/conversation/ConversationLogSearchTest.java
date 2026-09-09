package com.scivicslab.aiworkspace.conversation;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The parts of the conversation search that turn stored text into what a row shows, and the moves
 * from one search result to the turns and rows around it.
 *
 * <p>No {@code @QuarkusTest}: what a row shows is a pure function over strings, and the navigation
 * needs nothing from the container beyond a database path, which is a package-private field.</p>
 *
 * <p>The navigation runs against a temporary H2 file written here rather than against the merged
 * database on disk: the cases that matter are a turn spanning several rows and a gap in the turn
 * numbering, and both have to be stated by the test rather than waited for.</p>
 */
class ConversationLogSearchTest {

    @TempDir
    static Path tempDir;

    private static ConversationLogSearch search;

    /**
     * One conversation whose turns are not all one row and whose numbering has a gap, and a second
     * conversation, so that "the next row" cannot silently cross into another conversation.
     *
     * <pre>
     *   session 1: 10 turn1/step1/llm   11 turn1/step1/tool   12 turn1/step2/llm
     *              13 turn3/step1/llm   14 turn4/step1/llm
     *   session 2: 20 turn1/step1/llm
     * </pre>
     */
    @BeforeAll
    static void writeFixtureDatabase() throws Exception {
        Path db = tempDir.resolve("fixture-log");
        try (Connection c = DriverManager.getConnection("jdbc:h2:" + db.toAbsolutePath());
             Statement s = c.createStatement()) {
            s.execute("""
                    CREATE TABLE sessions (
                      id BIGINT PRIMARY KEY,
                      workflow_name VARCHAR,
                      command_line VARCHAR,
                      source_db VARCHAR)
                    """);
            s.execute("""
                    CREATE TABLE logs (
                      id BIGINT PRIMARY KEY,
                      session_id BIGINT,
                      timestamp TIMESTAMP,
                      node_id VARCHAR,
                      label VARCHAR,
                      action_name VARCHAR,
                      level VARCHAR,
                      message VARCHAR,
                      exit_code INT,
                      duration_ms BIGINT)
                    """);
            s.execute("INSERT INTO sessions VALUES "
                    + "(1, 'First conversation', 'java -jar /w/quarkus-chat-ui-2.5.0.jar "
                    + "-Dquarkus.http.port=28013', 'chat-ui-iolog-28013.mv.db'),"
                    + "(2, 'Second conversation', '', 'chat-ui-iolog-28014.mv.db')");
            insert(c, 10, 1, "turn1/step1/llm", "the model call of the first turn");
            insert(c, 11, 1, "turn1/step1/tool", "a tool the first turn ran");
            insert(c, 12, 1, "turn1/step2/llm", "the model call after that tool");
            insert(c, 13, 1, "turn3/step1/llm", "turn2 was never written");
            insert(c, 14, 1, "turn4/step1/llm", "the last turn of this conversation");
            insert(c, 20, 2, "turn1/step1/llm", "another conversation entirely");
        }
        search = new ConversationLogSearch();
        search.dbPath = db.toAbsolutePath().toString();
    }

    private static void insert(Connection c, long id, long sessionId, String label, String message)
            throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO logs (id, session_id, timestamp, label, level, message) "
                + "VALUES (?, ?, ?, ?, 'INFO', ?)")) {
            ps.setLong(1, id);
            ps.setLong(2, sessionId);
            ps.setTimestamp(3, Timestamp.valueOf("2026-09-09 10:0" + (id % 10) + ":00"));
            ps.setString(4, label);
            ps.setString(5, message);
            ps.executeUpdate();
        }
    }

    @Test
    void namesTheProgramAndPortFromTheCommandLine() {
        String commandLine = "/usr/bin/java -Dchat-ui.provider=claude -Dquarkus.http.port=28013 "
                + "-jar /home/devteam/works/quarkus-chat-ui.jar";
        assertThat(ConversationLogSearch.programOf(commandLine, "chat-ui-iolog-28013.mv.db"))
                .isEqualTo("quarkus-chat-ui:28013");
    }

    @Test
    void dropsTheVersionFromTheJarName() {
        String commandLine = "/usr/bin/java -Dquarkus.http.port=28012 "
                + "-jar /home/devteam/works/chat-ui-with-audit-trail-0.2.0-SNAPSHOT.jar";
        assertThat(ConversationLogSearch.programOf(commandLine, "chat-ui-iolog-28012.mv.db"))
                .isEqualTo("chat-ui-with-audit-trail:28012");
    }

    @Test
    void fallsBackToTheSourceFileWhenNoCommandLineWasRecorded() {
        // Every conversation recorded before the writing process started recording its command
        // line is in this state, which is most of what is in the merged database today.
        assertThat(ConversationLogSearch.programOf(null, "chat-ui-iolog-28010.mv.db"))
                .isEqualTo("chat-ui-iolog-28010");
    }

    @Test
    void saysUnknownWhenNeitherIsRecorded() {
        assertThat(ConversationLogSearch.programOf(null, null)).isEqualTo("unknown");
    }

    @Test
    void keepsTheWholeJarNameWhenItCarriesNoVersion() {
        assertThat(ConversationLogSearch.jarName("/home/devteam/works/html-saurus.jar"))
                .isEqualTo("html-saurus");
    }

    @Test
    void cutsTheTextAroundTheMatch() {
        String message = "a".repeat(400) + "needle" + "b".repeat(400);
        String snippet = ConversationLogSearch.snippet(message, "needle");
        assertThat(snippet).contains("needle").startsWith("…").endsWith("…");
        assertThat(snippet.length()).isLessThan(message.length());
    }

    @Test
    void findsTheMatchWhateverTheCase() {
        assertThat(ConversationLogSearch.snippet("The POJO-actor library", "pojo-actor"))
                .isEqualTo("The POJO-actor library");
    }

    @Test
    void putsAJapaneseMatchOnOneLine() {
        // Line breaks inside a turn must not split the snippet: the stored message holds the whole
        // request and response, newlines and all.
        String message = "前の行\n会話ログを全部データベースに残す\n次の行";
        assertThat(ConversationLogSearch.snippet(message, "会話ログ"))
                .isEqualTo("前の行 会話ログを全部データベースに残す 次の行");
    }

    @Test
    void answersEmptyForAMessageThatIsNotThere() {
        assertThat(ConversationLogSearch.snippet(null, "anything")).isEmpty();
    }

    @Test
    void turnView_showsEveryRowOfTheTurn_notOnlyTheOneThatMatched() {
        ConversationLogSearch.View view = search.turnView(11);

        assertThat(view.found()).isTrue();
        assertThat(view.turn()).isEqualTo("turn1");
        assertThat(view.rows()).extracting(ConversationLogSearch.Row::logId)
                .containsExactly(10L, 11L, 12L);
    }

    @Test
    void turnView_arrowsLeadToTheTurnsEitherSide_acrossAGapInTheNumbering() {
        ConversationLogSearch.View turn3 = search.turnView(13);

        assertThat(turn3.previousId()).as("back from turn3 reaches the last row of turn1")
                .isEqualTo(12);
        assertThat(turn3.previousLabel()).isEqualTo("turn1/step2/llm");
        assertThat(turn3.nextId()).isEqualTo(14);
        assertThat(turn3.nextLabel()).isEqualTo("turn4/step1/llm");
    }

    @Test
    void turnView_hasNoBackArrowOnTheFirstTurn_andNoForwardArrowOnTheLast() {
        assertThat(search.turnView(10).previousId()).isZero();
        assertThat(search.turnView(14).nextId()).isZero();
    }

    @Test
    void turnView_arrowsNeverLeaveTheConversation() {
        // Row 20 is the next id in the database, but it belongs to another conversation.
        assertThat(search.turnView(14).nextId()).isZero();
        assertThat(search.turnView(20).previousId()).isZero();
    }

    @Test
    void rowView_showsTheOneRow_andStepsInsideTheTurn() {
        ConversationLogSearch.View view = search.rowView(11);

        assertThat(view.found()).isTrue();
        assertThat(view.rows()).hasSize(1);
        assertThat(view.rows().get(0).label()).isEqualTo("turn1/step1/tool");
        assertThat(view.previousId()).isEqualTo(10);
        assertThat(view.nextId()).isEqualTo(12);
    }

    @Test
    void view_namesTheConversationAndTheProgramThatHeldIt() {
        ConversationLogSearch.View view = search.turnView(10);

        assertThat(view.conversation()).isEqualTo("First conversation");
        assertThat(view.program()).isEqualTo("quarkus-chat-ui:28013");
        assertThat(view.sessionId()).isEqualTo(1);
    }

    @Test
    void view_ofARowThatIsNotThere_isNotFound() {
        assertThat(search.turnView(999).found()).isFalse();
        assertThat(search.rowView(999).found()).isFalse();
    }

    @Test
    void turnView_carriesOnlyASummaryOfEachCall_notTheCallItself() {
        // Listing a turn is done to choose one of its calls. Sending every call whole to do that
        // is what made a thirty-call turn megabytes of JSON.
        ConversationLogSearch.View view = search.turnView(11);

        assertThat(view.rows()).allSatisfy(row -> {
            assertThat(row.message()).isEmpty();
            assertThat(row.summary()).isNotEmpty();
        });
    }

    @Test
    void rowView_carriesTheCallWhole() {
        ConversationLogSearch.Row call = search.rowView(11).rows().get(0);

        assertThat(call.message()).isEqualTo("a tool the first turn ran");
        assertThat(call.summary()).isEqualTo("a tool the first turn ran");
    }

    @Test
    void summarise_putsTheOpeningOnOneLineAndMarksWhatWasCut() {
        assertThat(ConversationLogSearch.summarise("first line\n\nsecond  line"))
                .isEqualTo("first line second line");
        assertThat(ConversationLogSearch.summarise("x".repeat(500)))
                .hasSize(201).endsWith("\u2026");
        assertThat(ConversationLogSearch.summarise(null)).isEmpty();
    }

    @Test
    void turnKey_isThePartBeforeTheFirstSlash() {
        assertThat(ConversationLogSearch.turnKey("turn7/step1/llm")).isEqualTo("turn7");
        assertThat(ConversationLogSearch.turnKey("turn10/step2/tool")).isEqualTo("turn10");
        assertThat(ConversationLogSearch.turnKey("whatever")).isEqualTo("whatever");
        assertThat(ConversationLogSearch.turnKey("")).isEmpty();
    }

    @Test
    void turnRows_ofTurn1_doNotSwallowTurn10() throws Exception {
        // The turn is matched with a trailing slash, so a prefix match cannot take a longer number.
        Path db = tempDir.resolve("prefix-log");
        try (Connection c = DriverManager.getConnection("jdbc:h2:" + db.toAbsolutePath());
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE sessions (id BIGINT PRIMARY KEY, workflow_name VARCHAR, "
                    + "command_line VARCHAR, source_db VARCHAR)");
            s.execute("CREATE TABLE logs (id BIGINT PRIMARY KEY, session_id BIGINT, "
                    + "timestamp TIMESTAMP, node_id VARCHAR, label VARCHAR, action_name VARCHAR, "
                    + "level VARCHAR, message VARCHAR, exit_code INT, duration_ms BIGINT)");
            s.execute("INSERT INTO sessions VALUES (1, 'c', '', 'chat-ui-iolog-1.mv.db')");
            insert(c, 1, 1, "turn1/step1/llm", "turn one");
            insert(c, 2, 1, "turn10/step1/llm", "turn ten");
        }
        ConversationLogSearch other = new ConversationLogSearch();
        other.dbPath = db.toAbsolutePath().toString();

        assertThat(other.turnView(1).rows()).extracting(ConversationLogSearch.Row::logId)
                .containsExactly(1L);
    }
}
