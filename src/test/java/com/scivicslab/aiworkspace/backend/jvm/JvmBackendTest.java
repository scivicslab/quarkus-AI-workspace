package com.scivicslab.aiworkspace.backend.jvm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for JvmBackend helper methods.
 */
class JvmBackendTest {

    // ---------------------------------------------------------------
    // jarMatches
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("jarMatches")
    class JarMatchesTest {

        @Test
        @DisplayName("full absolute path in args matches")
        void fullAbsolutePath_matches() {
            String[] args = {"-Dquarkus.http.port=28081", "-jar", "/home/devteam/ai-toolkit/quarkus-mcp-gateway.jar"};
            assertThat(JvmBackend.jarMatches("/home/devteam/ai-toolkit/quarkus-mcp-gateway.jar", args)).isTrue();
        }

        @Test
        @DisplayName("bare filename in args matches when resolvedJar is absolute")
        void bareFilename_matchesAbsoluteResolvedJar() {
            // Gateway started with relative path; quarkus-AI-workspace resolves to absolute
            String[] args = {"-Dquarkus.http.port=28081", "-jar", "quarkus-mcp-gateway.jar"};
            assertThat(JvmBackend.jarMatches("/home/devteam/ai-toolkit/quarkus-mcp-gateway.jar", args)).isTrue();
        }

        @Test
        @DisplayName("path ending with /filename in args matches")
        void relativeSubdir_matches() {
            String[] args = {"-jar", "toolkit/quarkus-mcp-gateway.jar"};
            assertThat(JvmBackend.jarMatches("/home/devteam/ai-toolkit/quarkus-mcp-gateway.jar", args)).isTrue();
        }

        @Test
        @DisplayName("different jar name does not match")
        void differentJar_noMatch() {
            String[] args = {"-jar", "quarkus-chat-ui.jar"};
            assertThat(JvmBackend.jarMatches("/home/devteam/ai-toolkit/quarkus-mcp-gateway.jar", args)).isFalse();
        }

        @Test
        @DisplayName("empty args array does not match")
        void emptyArgs_noMatch() {
            assertThat(JvmBackend.jarMatches("/home/devteam/ai-toolkit/quarkus-mcp-gateway.jar", new String[0])).isFalse();
        }

        @Test
        @DisplayName("partial filename substring does not match as bare filename")
        void partialFilename_noMatch() {
            // "gateway.jar" should not match "quarkus-mcp-gateway.jar"
            String[] args = {"-jar", "gateway.jar"};
            assertThat(JvmBackend.jarMatches("/home/devteam/ai-toolkit/quarkus-mcp-gateway.jar", args)).isFalse();
        }

        @Test
        @DisplayName("resolvedJar is a bare filename — full path in args matches via contains")
        void resolvedJarBareFilename_fullPathInArgs_matches() {
            String[] args = {"-jar", "/some/path/quarkus-mcp-gateway.jar"};
            assertThat(JvmBackend.jarMatches("quarkus-mcp-gateway.jar", args)).isTrue();
        }
    }

    // ---------------------------------------------------------------
    // ProcessSupervisor.resolveJarPath
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("resolveJarPath")
    class ResolveJarPathTest {

        @Test
        @DisplayName("absolute path is returned unchanged")
        void absolutePath_unchanged() {
            String path = "/home/devteam/ai-toolkit/quarkus-mcp-gateway.jar";
            assertThat(ProcessSupervisor.resolveJarPath(path)).isEqualTo(path);
        }

        @Test
        @DisplayName("relative path is resolved against user.dir")
        void relativePath_resolvedAgainstUserDir() {
            String userDir = System.getProperty("user.dir");
            String result = ProcessSupervisor.resolveJarPath("quarkus-mcp-gateway.jar");
            assertThat(result).isEqualTo(userDir + "/quarkus-mcp-gateway.jar");
        }

        @Test
        @DisplayName("null input returns null")
        void nullInput_returnsNull() {
            assertThat(ProcessSupervisor.resolveJarPath(null)).isNull();
        }

        @Test
        @DisplayName("blank input returns blank")
        void blankInput_returnsBlank() {
            assertThat(ProcessSupervisor.resolveJarPath("   ")).isEqualTo("   ");
        }
    }

    // ---------------------------------------------------------------
    // parsePort — user-supplied launch port
    // ---------------------------------------------------------------

    @Nested
    @DisplayName("parsePort")
    class ParsePortTest {

        @Test
        @DisplayName("valid port is parsed")
        void validPort_parsed() {
            assertThat(JvmBackend.parsePort("28100")).isEqualTo(28100);
        }

        @Test
        @DisplayName("surrounding whitespace is trimmed")
        void whitespace_trimmed() {
            assertThat(JvmBackend.parsePort("  9000 ")).isEqualTo(9000);
        }

        @Test
        @DisplayName("boundary values 1 and 65535 are valid")
        void boundaries_valid() {
            assertThat(JvmBackend.parsePort("1")).isEqualTo(1);
            assertThat(JvmBackend.parsePort("65535")).isEqualTo(65535);
        }

        @Test
        @DisplayName("null returns null")
        void nullInput_null() {
            assertThat(JvmBackend.parsePort(null)).isNull();
        }

        @Test
        @DisplayName("non-numeric returns null")
        void nonNumeric_null() {
            assertThat(JvmBackend.parsePort("abc")).isNull();
        }

        @Test
        @DisplayName("out-of-range values return null")
        void outOfRange_null() {
            assertThat(JvmBackend.parsePort("0")).isNull();
            assertThat(JvmBackend.parsePort("65536")).isNull();
            assertThat(JvmBackend.parsePort("-5")).isNull();
        }
    }
}
