package com.scivicslab.lxdpups.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ServiceProgressTest {

    @Test
    void idleFactoryReturnsExpectedState() {
        var sp = ServiceProgress.idle("test-svc");

        assertEquals("test-svc", sp.name());
        assertEquals("idle", sp.phase());
        assertTrue(sp.messages().isEmpty());
        assertTrue(sp.done());
        assertTrue(sp.success());
    }

    @Test
    void constructorPreservesAllFields() {
        var messages = List.of("Step 1", "Step 2");
        var sp = new ServiceProgress("svc-01", "launching", messages, false, false);

        assertEquals("svc-01", sp.name());
        assertEquals("launching", sp.phase());
        assertEquals(2, sp.messages().size());
        assertFalse(sp.done());
        assertFalse(sp.success());
    }

    @Test
    void completedWithFailure() {
        var sp = new ServiceProgress("svc-01", "failed", List.of("Error occurred"), true, false);

        assertTrue(sp.done());
        assertFalse(sp.success());
        assertEquals("failed", sp.phase());
    }
}
