package com.scivicslab.lxdpups.service;

import com.scivicslab.lxdpups.config.PortalConfig;
import com.scivicslab.lxdpups.config.PortalConfigLoader;
import com.scivicslab.lxdpups.model.ContainerLifecycleState;
import com.scivicslab.lxdpups.model.ContainerRecord;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Periodically checks container timeouts and triggers cleanup.
 *
 * Three timeout types:
 *   - FAILED retention: auto-delete FAILED containers after configured minutes
 *   - Idle timeout: auto-stop READY containers with no activity for configured minutes
 *   - Max lifetime: auto-stop any container exceeding configured lifetime
 *
 * A value of 0 disables that particular timer.
 */
@ApplicationScoped
public class ContainerTimeoutManager {

    private static final Logger LOG = Logger.getLogger(ContainerTimeoutManager.class.getName());

    @Inject
    PortalConfigLoader configLoader;

    /**
     * Checks which containers should be timed out based on current time and config.
     * Returns a list of container names that should be stopped/deleted.
     *
     * This method is pure logic (no side effects) for testability.
     */
    public static List<TimeoutAction> checkTimeouts(Map<String, ContainerRecord> records,
                                                     Instant now,
                                                     int idleTimeoutMinutes,
                                                     int maxLifetimeMinutes,
                                                     int failedRetentionMinutes) {
        List<TimeoutAction> actions = new ArrayList<>();

        for (Map.Entry<String, ContainerRecord> entry : records.entrySet()) {
            ContainerRecord record = entry.getValue();
            ContainerLifecycleState state = record.getState();

            // FAILED retention check
            if (state == ContainerLifecycleState.FAILED && failedRetentionMinutes > 0) {
                Instant failedAt = record.getFailedAt();
                if (failedAt != null) {
                    long minutesSinceFailed = Duration.between(failedAt, now).toMinutes();
                    if (minutesSinceFailed >= failedRetentionMinutes) {
                        actions.add(new TimeoutAction(record.getName(), record.getRemote(),
                                TimeoutReason.FAILED_RETENTION));
                        continue;
                    }
                }
            }

            // Idle timeout check (READY containers only)
            if (state == ContainerLifecycleState.READY && idleTimeoutMinutes > 0) {
                long minutesSinceActivity = Duration.between(record.getLastActivityAt(), now).toMinutes();
                if (minutesSinceActivity >= idleTimeoutMinutes) {
                    actions.add(new TimeoutAction(record.getName(), record.getRemote(),
                            TimeoutReason.IDLE_TIMEOUT));
                    continue;
                }
            }

            // Max lifetime check (all non-terminal states)
            if (!state.isTerminal() && maxLifetimeMinutes > 0) {
                long minutesSinceCreation = Duration.between(record.getCreatedAt(), now).toMinutes();
                if (minutesSinceCreation >= maxLifetimeMinutes) {
                    actions.add(new TimeoutAction(record.getName(), record.getRemote(),
                            TimeoutReason.MAX_LIFETIME));
                }
            }
        }

        return actions;
    }

    public enum TimeoutReason {
        FAILED_RETENTION,
        IDLE_TIMEOUT,
        MAX_LIFETIME
    }

    public record TimeoutAction(String name, String remote, TimeoutReason reason) {}
}
