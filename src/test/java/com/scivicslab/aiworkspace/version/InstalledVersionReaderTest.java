package com.scivicslab.aiworkspace.version;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What the Installed line shows is the version in the name of the jar file the symbolic link
 * points at. These pin how that name is read.
 */
@Tag("ToolVersions_260907_oo01")
@DisplayName("InstalledVersionReader — the version in the jar file's name")
class InstalledVersionReaderTest {

    @Test
    void versionOf_plainName_returnsWhatIsBetweenTheNameAndTheExtension() {
        assertEquals("2.3.0-SNAPSHOT",
                InstalledVersionReader.versionOf("html-saurus-2.3.0-SNAPSHOT.jar", "html-saurus.jar"));
    }

    @Test
    void versionOf_releaseVersion_returnsIt() {
        assertEquals("2.5.0",
                InstalledVersionReader.versionOf("turing-workflow-editor-2.5.0.jar",
                        "turing-workflow-editor.jar"));
    }

    @Test
    void versionOf_quarkusRunnerJar_dropsTheRunnerSuffix() {
        // The pom declares 1.1.0-SNAPSHOT. Keeping -runner would put the same version on the
        // Installed line and the Latest SNAPSHOT line in two different spellings.
        assertEquals("1.1.0-SNAPSHOT",
                InstalledVersionReader.versionOf("code-raptor-1.1.0-SNAPSHOT-runner.jar",
                        "code-raptor.jar"));
    }

    @Test
    void versionOf_nameThatIsNotThisTool_returnsEmpty() {
        assertEquals("",
                InstalledVersionReader.versionOf("something-else-1.0.0.jar", "html-saurus.jar"));
    }

    @Test
    void versionOf_noVersionAtAll_returnsEmpty() {
        assertEquals("", InstalledVersionReader.versionOf("html-saurus.jar", "html-saurus.jar"));
    }

    @Test
    void versionOf_notAJar_returnsEmpty() {
        assertEquals("", InstalledVersionReader.versionOf("html-saurus-2.3.0.zip", "html-saurus.jar"));
    }

    @Test
    void versionOf_twelveDigitBuildStamp_isNotPartOfTheVersion() {
        assertEquals("3.0.0-SNAPSHOT", InstalledVersionReader.versionOf(
                "chat-ui-with-audit-trail-3.0.0-SNAPSHOT-261118230512.jar", "chat-ui-with-audit-trail.jar"));
    }

    @Test
    void versionOf_tenDigitStampOfAFilePlacedByHand_isNotPartOfTheVersion() {
        assertEquals("5.0.0-SNAPSHOT", InstalledVersionReader.versionOf(
                "quarkus-AI-workspace-5.0.0-SNAPSHOT-2611181458.jar", "quarkus-AI-workspace.jar"));
    }

    @Test
    void versionOf_branchLinkAndBranchFile_readTheVersion() {
        assertEquals("3.0.0-SNAPSHOT", InstalledVersionReader.versionOf(
                "chat-ui-with-audit-trail-3.0.0-SNAPSHOT-261118231047@v5.x.jar", "chat-ui-with-audit-trail@v5.x.jar"));
    }

    @Test
    void versionOf_runnerJarWithAStamp_dropsBoth() {
        assertEquals("1.1.0-SNAPSHOT", InstalledVersionReader.versionOf(
                "code-raptor-1.1.0-SNAPSHOT-runner-261118230512.jar", "code-raptor.jar"));
    }
}
