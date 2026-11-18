package com.scivicslab.aiworkspace.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Build Snapshot places each build's jar under a name of its own, so a rebuild never writes to a file a running JVM
 * has open, and a branch entry's files never meet the default branch's (DashboardBuilder_261118_oo01).
 */
class SnapshotInstallTest {

    @TempDir
    Path dir;

    private Path built(String content) throws Exception {
        Path target = Files.createDirectories(dir.resolve("repo/target"));
        Path jar = target.resolve("chat-ui-with-audit-trail-3.0.0-SNAPSHOT.jar");
        Files.writeString(jar, content);
        return jar;
    }

    @Test
    void aRebuildPlacesANewFile_movesOnlyTheLink_andLeavesTheOldFileAsItWas() throws Exception {
        Path works = dir.resolve("works");
        Path first = SnapshotBuildService.install(works, built("first"), "chat-ui-with-audit-trail.jar", "261118230512", null);
        Path second = SnapshotBuildService.install(works, built("second"), "chat-ui-with-audit-trail.jar", "261118231047", null);
        assertEquals("chat-ui-with-audit-trail-3.0.0-SNAPSHOT-261118230512.jar", first.getFileName().toString());
        assertEquals("first", Files.readString(first), "the file a running JVM may have open is not written to");
        assertEquals("second", Files.readString(second));
        assertEquals(Path.of("chat-ui-with-audit-trail-3.0.0-SNAPSHOT-261118231047.jar"),
                Files.readSymbolicLink(works.resolve("chat-ui-with-audit-trail.jar")));
    }

    @Test
    void aBranchEntrysFilesAndLinkCarryTheBranch_andLeaveTheDefaultBranchsLinkAlone() throws Exception {
        Path works = dir.resolve("works");
        SnapshotBuildService.install(works, built("main"), "chat-ui-with-audit-trail.jar", "261118230512", null);
        Path v5 = SnapshotBuildService.install(works, built("v5"), "chat-ui-with-audit-trail@v5.x.jar", "261118230512", "v5.x");
        assertEquals("chat-ui-with-audit-trail-3.0.0-SNAPSHOT-261118230512@v5.x.jar", v5.getFileName().toString());
        assertEquals("main", Files.readString(works.resolve("chat-ui-with-audit-trail.jar")));
        assertEquals("v5", Files.readString(works.resolve("chat-ui-with-audit-trail@v5.x.jar")));
    }

    @Test
    void aFileOfTheSameNameIsNeverReplaced() throws Exception {
        Path works = dir.resolve("works");
        SnapshotBuildService.install(works, built("first"), "chat-ui-with-audit-trail.jar", "261118230512", null);
        assertThrows(FileAlreadyExistsException.class,
                () -> SnapshotBuildService.install(works, built("again"), "chat-ui-with-audit-trail.jar", "261118230512", null));
        assertTrue(Files.readString(works.resolve("chat-ui-with-audit-trail-3.0.0-SNAPSHOT-261118230512.jar")).equals("first"));
    }
}
