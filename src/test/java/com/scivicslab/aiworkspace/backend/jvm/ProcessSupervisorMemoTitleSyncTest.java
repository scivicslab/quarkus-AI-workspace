package com.scivicslab.aiworkspace.backend.jvm;

import com.scivicslab.aiworkspace.config.AiWorkspaceConfig;
import com.scivicslab.aiworkspace.model.SessionView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that a "title" launch param always wins over a freely-typed memo, so the
 * Active Sessions dashboard box stays permanently in sync with the session title.
 */
class ProcessSupervisorMemoTitleSyncTest {

    private static AiWorkspaceConfig.ToolDefinition minimalConfig() {
        return new AiWorkspaceConfig.ToolDefinition(
                "test-tool", null, 0, false, false, false,
                List.of(), List.of(), List.of(), null);
    }

    private static SessionView view(Map<String, String> launchParams, String storedMemo) {
        ProcessSupervisor supervisor = new ProcessSupervisor(minimalConfig(), 12345, launchParams);
        if (storedMemo != null) {
            supervisor.setMemo(storedMemo);
        }
        return supervisor.toSessionView((name, port) -> "http://localhost:" + port);
    }

    @Test
    @DisplayName("title param present and non-blank: memo mirrors the title")
    void titlePresent_memoMirrorsTitle() {
        SessionView v = view(Map.of("title", "My Debug Session"), "old manual memo");
        assertThat(v.memo()).isEqualTo("My Debug Session");
        assertThat(v.memoIsFromTitle()).isTrue();
    }

    @Test
    @DisplayName("no title param: memo falls back to the freely-typed memo")
    void noTitle_memoFallsBackToStoredMemo() {
        SessionView v = view(Map.of(), "hand-typed memo");
        assertThat(v.memo()).isEqualTo("hand-typed memo");
        assertThat(v.memoIsFromTitle()).isFalse();
    }

    @Test
    @DisplayName("title param present but blank: memo falls back to the freely-typed memo")
    void blankTitle_memoFallsBackToStoredMemo() {
        SessionView v = view(Map.of("title", "   "), "hand-typed memo");
        assertThat(v.memo()).isEqualTo("hand-typed memo");
        assertThat(v.memoIsFromTitle()).isFalse();
    }
}
