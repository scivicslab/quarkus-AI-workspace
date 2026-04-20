package com.scivicslab.serviceportal.backend.jvm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ProcessSupervisor helper methods.
 */
class ProcessSupervisorTest {

    @Test
    @DisplayName("expandEnvVars: known env var is substituted")
    void expandEnvVars_knownVar_substituted() {
        String path = System.getenv("PATH");
        String result = ProcessSupervisor.expandEnvVars("${PATH}");
        assertThat(result).isEqualTo(path);
    }

    @Test
    @DisplayName("expandEnvVars: unknown env var becomes empty string")
    void expandEnvVars_unknownVar_becomesEmpty() {
        String result = ProcessSupervisor.expandEnvVars("${_DEFINITELY_NOT_SET_VAR_12345}");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("expandEnvVars: mixed text with unknown var removes the placeholder")
    void expandEnvVars_mixedText_unknownVarRemoved() {
        String result = ProcessSupervisor.expandEnvVars("prefix_${_NOT_SET_}_suffix");
        assertThat(result).isEqualTo("prefix__suffix");
    }

    @Test
    @DisplayName("expandEnvVars: null input returns null")
    void expandEnvVars_null_returnsNull() {
        assertThat(ProcessSupervisor.expandEnvVars(null)).isNull();
    }

    @Test
    @DisplayName("expandEnvVars: no placeholder passes through unchanged")
    void expandEnvVars_noPlaceholder_unchanged() {
        assertThat(ProcessSupervisor.expandEnvVars("http://localhost:8000"))
            .isEqualTo("http://localhost:8000");
    }
}
