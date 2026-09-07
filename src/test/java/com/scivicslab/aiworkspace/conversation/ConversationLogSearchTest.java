package com.scivicslab.aiworkspace.conversation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The parts of the conversation search that turn stored text into what a row shows.
 *
 * <p>No {@code @QuarkusTest}: these are pure functions over strings, and the database they read
 * from is not involved.</p>
 */
class ConversationLogSearchTest {

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
}
