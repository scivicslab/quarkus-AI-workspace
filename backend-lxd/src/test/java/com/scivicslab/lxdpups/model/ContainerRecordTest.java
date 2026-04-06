package com.scivicslab.lxdpups.model;

import org.junit.jupiter.api.Test;

import static com.scivicslab.lxdpups.model.ContainerLifecycleState.*;
import static org.junit.jupiter.api.Assertions.*;

class ContainerRecordTest {

    @Test
    void newRecordHasCorrectDefaults() {
        ContainerRecord record = new ContainerRecord("test-01", "lxd-pups/ai-tools", "local", CREATING);
        assertEquals("test-01", record.getName());
        assertEquals("lxd-pups/ai-tools", record.getImage());
        assertEquals("local", record.getRemote());
        assertEquals(CREATING, record.getState());
        assertNull(record.getIp());
        assertNull(record.getFailedAt());
        assertNull(record.getFailureReason());
        assertNotNull(record.getCreatedAt());
        assertNotNull(record.getLastActivityAt());
    }

    @Test
    void withStateProducesNewInstance() {
        ContainerRecord original = new ContainerRecord("test-01", "lxd-pups/ai-tools", "local", CREATING);
        ContainerRecord next = original.withState(STARTING);

        assertEquals(CREATING, original.getState());
        assertEquals(STARTING, next.getState());
        assertNotSame(original, next);
    }

    @Test
    void withStatePreservesFields() {
        ContainerRecord original = new ContainerRecord("test-01", "lxd-pups/ai-tools", "local", CREATING);
        ContainerRecord withIp = original.withIp("10.0.0.5");
        ContainerRecord next = withIp.withState(STARTING);

        assertEquals("test-01", next.getName());
        assertEquals("lxd-pups/ai-tools", next.getImage());
        assertEquals("local", next.getRemote());
        assertEquals("10.0.0.5", next.getIp());
    }

    @Test
    void withStateValidatesTransition() {
        ContainerRecord record = new ContainerRecord("test-01", "lxd-pups/ai-tools", "local", CREATING);
        assertThrows(IllegalStateException.class, () -> record.withState(READY));
    }

    @Test
    void withFailedSetsReasonAndTimestamp() {
        ContainerRecord record = new ContainerRecord("test-01", "lxd-pups/ai-tools", "local", CREATING);
        ContainerRecord failed = record.withFailed("Download timeout");

        assertEquals(FAILED, failed.getState());
        assertEquals("Download timeout", failed.getFailureReason());
        assertNotNull(failed.getFailedAt());
    }

    @Test
    void withFailedValidatesTransition() {
        ContainerRecord record = new ContainerRecord("test-01", "lxd-pups/ai-tools", "local", STOPPED);
        assertThrows(IllegalStateException.class, () -> record.withFailed("Should not work"));
    }

    @Test
    void withIpUpdatesActivityTime() {
        ContainerRecord record = new ContainerRecord("test-01", "lxd-pups/ai-tools", "local", CREATING);
        ContainerRecord withIp = record.withIp("10.0.0.5");

        assertEquals("10.0.0.5", withIp.getIp());
        assertNull(record.getIp());
        assertTrue(withIp.getLastActivityAt().compareTo(record.getLastActivityAt()) >= 0);
    }

    @Test
    void withActivityUpdatesTimestamp() {
        ContainerRecord record = new ContainerRecord("test-01", "lxd-pups/ai-tools", "local", CREATING);
        ContainerRecord active = record.withActivity();

        assertTrue(active.getLastActivityAt().compareTo(record.getLastActivityAt()) >= 0);
        assertEquals(record.getState(), active.getState());
    }

    @Test
    void fullLifecycleTransition() {
        ContainerRecord record = new ContainerRecord("test-01", "lxd-pups/ai-tools", "local", CREATING);
        ContainerRecord starting = record.withState(STARTING);
        ContainerRecord ready = starting.withState(READY);
        ContainerRecord stopping = ready.withState(STOPPING);
        ContainerRecord stopped = stopping.withState(STOPPED);

        assertEquals(CREATING, record.getState());
        assertEquals(STARTING, starting.getState());
        assertEquals(READY, ready.getState());
        assertEquals(STOPPING, stopping.getState());
        assertEquals(STOPPED, stopped.getState());
    }

    @Test
    void failedToStoppingTransition() {
        ContainerRecord record = new ContainerRecord("test-01", "lxd-pups/ai-tools", "local", CREATING);
        ContainerRecord failed = record.withFailed("Health check timeout");
        ContainerRecord stopping = failed.withState(STOPPING);
        ContainerRecord stopped = stopping.withState(STOPPED);

        assertEquals(FAILED, failed.getState());
        assertEquals(STOPPING, stopping.getState());
        assertEquals(STOPPED, stopped.getState());
    }

    @Test
    void failedToStoppedDirectTransition() {
        ContainerRecord record = new ContainerRecord("test-01", "lxd-pups/ai-tools", "local", CREATING);
        ContainerRecord failed = record.withFailed("Auto-cleanup");
        ContainerRecord stopped = failed.withState(STOPPED);

        assertEquals(STOPPED, stopped.getState());
    }

    @Test
    void toStringIncludesKeyFields() {
        ContainerRecord record = new ContainerRecord("test-01", "lxd-pups/ai-tools", "local", CREATING);
        String str = record.toString();
        assertTrue(str.contains("test-01"));
        assertTrue(str.contains("CREATING"));
        assertTrue(str.contains("lxd-pups/ai-tools"));
    }
}
