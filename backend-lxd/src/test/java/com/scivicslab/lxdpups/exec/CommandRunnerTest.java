package com.scivicslab.lxdpups.exec;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandRunnerTest {

    private final CommandRunner runner = new CommandRunner();

    @Test
    void runEchoReturnsOutput() {
        var result = runner.run(List.of("echo", "hello"));
        assertTrue(result.success());
        assertEquals("hello", result.stdout().strip());
    }

    @Test
    void runTrueReturnsZero() {
        var result = runner.run(List.of("true"));
        assertTrue(result.success());
        assertEquals(0, result.exitCode());
    }

    @Test
    void runFalseReturnsNonZero() {
        var result = runner.run(List.of("false"));
        assertFalse(result.success());
        assertNotEquals(0, result.exitCode());
    }

    @Test
    void runNonexistentCommandReturnsError() {
        var result = runner.run(List.of("this-command-does-not-exist-12345"));
        assertFalse(result.success());
        assertEquals(-1, result.exitCode());
    }
}
