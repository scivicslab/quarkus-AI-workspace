package com.scivicslab.lxdpups.exec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CommandResultTest {

    @Test
    void successWhenExitCodeZero() {
        var result = new CommandResult(0, "ok", "");
        assertTrue(result.success());
    }

    @Test
    void failureWhenExitCodeNonZero() {
        var result = new CommandResult(1, "", "error");
        assertFalse(result.success());
    }

    @Test
    void failureWhenExitCodeNegative() {
        var result = new CommandResult(-1, "", "timeout");
        assertFalse(result.success());
    }
}
