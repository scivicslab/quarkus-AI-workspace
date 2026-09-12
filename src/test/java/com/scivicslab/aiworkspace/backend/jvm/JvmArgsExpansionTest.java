package com.scivicslab.aiworkspace.backend.jvm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit test for {@code CompanionJars_260912_oo01}: a registry entry's {@code jvmArgs} are expanded
 * like the form fields, so {@code ${HOME}} in {@code -Dchat-ui.plugins=...} becomes an absolute path.
 */
class JvmArgsExpansionTest {

    @Test
    void expandJvmArgs_replacesHomeAndLeavesPlainFlags() {
        String home = System.getenv("HOME");
        List<String> out = ProcessSupervisor.expandJvmArgs(List.of(
                "-Xmx2g",
                "-Dchat-ui.plugins=${HOME}/works/a.jar,${HOME}/works/b.jar"));
        assertEquals("-Xmx2g", out.get(0));
        assertEquals("-Dchat-ui.plugins=" + home + "/works/a.jar," + home + "/works/b.jar", out.get(1));
        assertEquals(List.of(), ProcessSupervisor.expandJvmArgs(null));
    }
}
