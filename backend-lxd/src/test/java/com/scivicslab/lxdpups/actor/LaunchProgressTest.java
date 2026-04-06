package com.scivicslab.lxdpups.actor;

import com.scivicslab.lxdpups.model.ServiceProgress;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class LaunchProgressTest {

    @Test
    void initialStateIsQueued() {
        var progress = new LaunchProgress("test-01", "lxd-pups/ai-tools");

        assertEquals("test-01", progress.getName());
        assertEquals("lxd-pups/ai-tools", progress.getTemplate());
        assertFalse(progress.isDone());
        assertFalse(progress.isSuccess());
        assertNotNull(progress.getStartedAt());
        assertTrue(progress.getStartedAt().isBefore(Instant.now().plusSeconds(1)));
    }

    @Test
    void setPhaseUpdatesPhase() {
        var progress = new LaunchProgress("test-01", "lxd-pups/ai-tools");
        progress.setPhase("launching");

        var sp = progress.toServiceProgress();
        assertEquals("launching", sp.phase());
    }

    @Test
    void addMessageAccumulates() {
        var progress = new LaunchProgress("test-01", "lxd-pups/ai-tools");
        progress.addMessage("Step 1 done");
        progress.addMessage("Step 2 done");

        var sp = progress.toServiceProgress();
        assertEquals(2, sp.messages().size());
        assertEquals("Step 1 done", sp.messages().get(0));
        assertEquals("Step 2 done", sp.messages().get(1));
    }

    @Test
    void completeSuccessSetsDoneAndSuccess() {
        var progress = new LaunchProgress("test-01", "lxd-pups/ai-tools");
        progress.complete(true);

        assertTrue(progress.isDone());
        assertTrue(progress.isSuccess());
    }

    @Test
    void completeFailureSetsDoneNotSuccess() {
        var progress = new LaunchProgress("test-01", "lxd-pups/ai-tools");
        progress.complete(false);

        assertTrue(progress.isDone());
        assertFalse(progress.isSuccess());
    }

    @Test
    void toServiceProgressReflectsCurrentState() {
        var progress = new LaunchProgress("test-01", "lxd-pups/ai-tools");
        progress.setPhase("health-check");
        progress.addMessage("Waiting for TCP");
        progress.complete(true);

        ServiceProgress sp = progress.toServiceProgress();
        assertEquals("test-01", sp.name());
        assertEquals("health-check", sp.phase());
        assertEquals(1, sp.messages().size());
        assertTrue(sp.done());
        assertTrue(sp.success());
    }

    @Test
    void toServiceProgressMessagesAreDefensiveCopy() {
        var progress = new LaunchProgress("test-01", "lxd-pups/ai-tools");
        progress.addMessage("msg1");

        ServiceProgress sp = progress.toServiceProgress();
        // Modifying original should not affect the copy
        progress.addMessage("msg2");

        assertEquals(1, sp.messages().size());
    }
}
