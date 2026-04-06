package com.scivicslab.lxdpups.service;

import com.scivicslab.lxdpups.model.ContainerLifecycleState;
import com.scivicslab.lxdpups.model.ContainerRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContainerTimeoutManagerTest {

    private static final int IDLE_TIMEOUT = 1440;       // 24h
    private static final int MAX_LIFETIME = 10080;      // 7d
    private static final int FAILED_RETENTION = 30;     // 30min

    @Test
    void failedContainerPastRetentionIsDeleted() {
        ContainerRecord record = new ContainerRecord("test-01", "lxd-pups/ai-tools", "local",
                ContainerLifecycleState.CREATING);
        ContainerRecord failed = record.withFailed("Health check timeout");

        // Simulate 31 minutes after failure
        Instant now = failed.getFailedAt().plusSeconds(31 * 60);

        var actions = ContainerTimeoutManager.checkTimeouts(
                Map.of("test-01", failed), now, IDLE_TIMEOUT, MAX_LIFETIME, FAILED_RETENTION);

        assertEquals(1, actions.size());
        assertEquals("test-01", actions.get(0).name());
        assertEquals(ContainerTimeoutManager.TimeoutReason.FAILED_RETENTION, actions.get(0).reason());
    }

    @Test
    void failedContainerWithinRetentionIsKept() {
        ContainerRecord record = new ContainerRecord("test-01", "lxd-pups/ai-tools", "local",
                ContainerLifecycleState.CREATING);
        ContainerRecord failed = record.withFailed("Health check timeout");

        // 15 minutes after failure (within 30min retention)
        Instant now = failed.getFailedAt().plusSeconds(15 * 60);

        var actions = ContainerTimeoutManager.checkTimeouts(
                Map.of("test-01", failed), now, IDLE_TIMEOUT, MAX_LIFETIME, FAILED_RETENTION);

        assertTrue(actions.isEmpty());
    }

    @Test
    void readyContainerPastIdleTimeoutIsStopped() {
        ContainerRecord record = new ContainerRecord("test-01", "lxd-pups/ai-tools", "local",
                ContainerLifecycleState.CREATING);
        ContainerRecord ready = record.withState(ContainerLifecycleState.STARTING)
                .withState(ContainerLifecycleState.READY);

        // 25 hours after last activity
        Instant now = ready.getLastActivityAt().plusSeconds(25 * 60 * 60);

        var actions = ContainerTimeoutManager.checkTimeouts(
                Map.of("test-01", ready), now, IDLE_TIMEOUT, MAX_LIFETIME, FAILED_RETENTION);

        assertEquals(1, actions.size());
        assertEquals(ContainerTimeoutManager.TimeoutReason.IDLE_TIMEOUT, actions.get(0).reason());
    }

    @Test
    void readyContainerWithRecentActivityIsKept() {
        ContainerRecord record = new ContainerRecord("test-01", "lxd-pups/ai-tools", "local",
                ContainerLifecycleState.CREATING);
        ContainerRecord ready = record.withState(ContainerLifecycleState.STARTING)
                .withState(ContainerLifecycleState.READY)
                .withActivity();

        // 1 hour after last activity (within 24h idle timeout)
        Instant now = ready.getLastActivityAt().plusSeconds(60 * 60);

        var actions = ContainerTimeoutManager.checkTimeouts(
                Map.of("test-01", ready), now, IDLE_TIMEOUT, MAX_LIFETIME, FAILED_RETENTION);

        assertTrue(actions.isEmpty());
    }

    @Test
    void containerPastMaxLifetimeIsStopped() {
        ContainerRecord record = new ContainerRecord("test-01", "lxd-pups/ai-tools", "local",
                ContainerLifecycleState.CREATING);
        ContainerRecord ready = record.withState(ContainerLifecycleState.STARTING)
                .withState(ContainerLifecycleState.READY);

        // 8 days after creation (past 7d max lifetime)
        Instant now = ready.getCreatedAt().plusSeconds(8L * 24 * 60 * 60);

        var actions = ContainerTimeoutManager.checkTimeouts(
                Map.of("test-01", ready), now, 0, MAX_LIFETIME, FAILED_RETENTION);

        assertEquals(1, actions.size());
        assertEquals(ContainerTimeoutManager.TimeoutReason.MAX_LIFETIME, actions.get(0).reason());
    }

    @Test
    void disabledTimeoutDoesNotTrigger() {
        ContainerRecord record = new ContainerRecord("test-01", "lxd-pups/ai-tools", "local",
                ContainerLifecycleState.CREATING);
        ContainerRecord ready = record.withState(ContainerLifecycleState.STARTING)
                .withState(ContainerLifecycleState.READY);

        // 100 days after creation
        Instant now = ready.getCreatedAt().plusSeconds(100L * 24 * 60 * 60);

        // All timeouts disabled (0)
        var actions = ContainerTimeoutManager.checkTimeouts(
                Map.of("test-01", ready), now, 0, 0, 0);

        assertTrue(actions.isEmpty());
    }

    @Test
    void stoppedContainerIsIgnored() {
        ContainerRecord record = new ContainerRecord("test-01", "lxd-pups/ai-tools", "local",
                ContainerLifecycleState.CREATING);
        ContainerRecord stopped = record.withState(ContainerLifecycleState.STARTING)
                .withState(ContainerLifecycleState.READY)
                .withState(ContainerLifecycleState.STOPPING)
                .withState(ContainerLifecycleState.STOPPED);

        Instant now = stopped.getCreatedAt().plusSeconds(100L * 24 * 60 * 60);

        var actions = ContainerTimeoutManager.checkTimeouts(
                Map.of("test-01", stopped), now, IDLE_TIMEOUT, MAX_LIFETIME, FAILED_RETENTION);

        assertTrue(actions.isEmpty());
    }

    @Test
    void multipleContainersProcessedIndependently() {
        ContainerRecord ready = new ContainerRecord("active-01", "lxd-pups/ai-tools", "local",
                ContainerLifecycleState.CREATING)
                .withState(ContainerLifecycleState.STARTING)
                .withState(ContainerLifecycleState.READY);

        ContainerRecord failed = new ContainerRecord("failed-01", "lxd-pups/jupyter", "local",
                ContainerLifecycleState.CREATING)
                .withFailed("Timeout");

        // 31 minutes later — FAILED should be cleaned up, READY should be fine
        Instant now = failed.getFailedAt().plusSeconds(31 * 60);

        var actions = ContainerTimeoutManager.checkTimeouts(
                Map.of("active-01", ready, "failed-01", failed),
                now, IDLE_TIMEOUT, MAX_LIFETIME, FAILED_RETENTION);

        assertEquals(1, actions.size());
        assertEquals("failed-01", actions.get(0).name());
    }

    @Test
    void idleTimeoutOnlyAppliesToReadyContainers() {
        ContainerRecord creating = new ContainerRecord("test-01", "lxd-pups/ai-tools", "local",
                ContainerLifecycleState.CREATING);

        // 25 hours after creation
        Instant now = creating.getCreatedAt().plusSeconds(25 * 60 * 60);

        var actions = ContainerTimeoutManager.checkTimeouts(
                Map.of("test-01", creating), now, IDLE_TIMEOUT, 0, 0);

        // CREATING should not trigger idle timeout (only READY does)
        assertTrue(actions.isEmpty());
    }
}
