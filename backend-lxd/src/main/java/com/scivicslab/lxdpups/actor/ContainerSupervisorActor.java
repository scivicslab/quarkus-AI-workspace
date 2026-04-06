package com.scivicslab.lxdpups.actor;

import com.scivicslab.lxdpups.model.ContainerInfo;
import com.scivicslab.lxdpups.model.ContainerLifecycleState;
import com.scivicslab.lxdpups.model.ContainerRecord;
import com.scivicslab.lxdpups.model.ImageInfo;
import com.scivicslab.lxdpups.model.ServiceProgress;
import com.scivicslab.lxdpups.service.ContainerManager;
import com.scivicslab.pojoactor.core.ActorRef;

import java.util.*;
import java.util.logging.Logger;

/**
 * Supervisor actor for all container operations.
 * <p>
 * Always responsive to ask() queries (never blocks on lxc commands).
 * Delegates blocking work (lxc launch) to child LaunchWorkerActor.
 * </p>
 * <p>
 * Pattern follows k8s-pups SessionManagerActor:
 * Supervisor holds state, child actors do heavy lifting.
 * </p>
 */
public class ContainerSupervisorActor {

    private static final Logger LOG = Logger.getLogger(ContainerSupervisorActor.class.getName());

    private final ContainerManager containerManager;
    private Runnable onLaunchDone;

    /** name -> child LaunchWorkerActor for in-progress launches */
    private final Map<String, ActorRef<LaunchWorkerActor>> activeLaunches = new HashMap<>();

    /** name -> launch progress (shared with workers, readable without blocking) */
    private final Map<String, LaunchProgress> launchProgressMap = new LinkedHashMap<>();

    /** name -> container lifecycle record (the authoritative state) */
    private final Map<String, ContainerRecord> containerRecords = new LinkedHashMap<>();

    public ContainerSupervisorActor(ContainerManager containerManager) {
        this.containerManager = containerManager;
    }

    /**
     * Set a callback to run when any launch completes (e.g. refresh StatusPoller).
     */
    public void setOnLaunchDone(Runnable callback) {
        this.onLaunchDone = callback;
    }

    // ── Launch operations ──

    /**
     * Request a container launch. Creates a child LaunchWorkerActor and delegates.
     * Returns immediately — never blocks.
     *
     * @return error message if rejected, null if accepted
     */
    public String requestLaunch(ActorRef<ContainerSupervisorActor> self,
                                String template, String name, String remote) {
        // Reject if already launching this name
        if (activeLaunches.containsKey(name)) {
            return "Launch already in progress for '" + name + "'";
        }

        // Reject if container with this name already exists
        for (var c : containerManager.listAll(remote)) {
            if (name.equals(c.name())) {
                return "Container '" + name + "' already exists";
            }
        }

        // Create progress tracker and lifecycle record
        var progress = new LaunchProgress(name, template);
        launchProgressMap.put(name, progress);
        containerRecords.put(name, new ContainerRecord(
                name, template, remote, ContainerLifecycleState.CREATING));

        // Create child worker actor (pass supervisor ref for completion callback)
        var worker = new LaunchWorkerActor(containerManager, progress, self);
        ActorRef<LaunchWorkerActor> workerRef = self.createChild("launch-" + name, worker);
        activeLaunches.put(name, workerRef);

        // Tell worker to start (async, returns immediately)
        workerRef.tell(w -> w.doLaunch(template, name, remote));

        LOG.info("Launch requested: " + name + " from " + template);
        return null; // accepted
    }

    /**
     * Called by worker (via tell to supervisor) when launch completes.
     */
    public void onLaunchComplete(String name, boolean success) {
        var workerRef = activeLaunches.remove(name);
        if (workerRef != null) {
            workerRef.close();
        }

        // Update lifecycle record
        ContainerRecord record = containerRecords.get(name);
        if (record != null) {
            if (success) {
                // CREATING -> STARTING -> READY (worker handles STARTING internally)
                try {
                    ContainerRecord ready = record.withState(ContainerLifecycleState.STARTING)
                            .withState(ContainerLifecycleState.READY);
                    containerRecords.put(name, ready);
                } catch (IllegalStateException e) {
                    // If already in STARTING (worker set it), just go to READY
                    try {
                        containerRecords.put(name, record.withState(ContainerLifecycleState.READY));
                    } catch (IllegalStateException e2) {
                        LOG.warning("Could not transition " + name + " to READY: " + e2.getMessage());
                    }
                }
            } else {
                // Transition to FAILED — preserve container for investigation
                try {
                    containerRecords.put(name, record.withFailed("Launch failed"));
                } catch (IllegalStateException e) {
                    LOG.warning("Could not transition " + name + " to FAILED: " + e.getMessage());
                }
            }
        }

        LOG.info("Launch completed: " + name + " success=" + success);
        if (onLaunchDone != null) {
            try {
                onLaunchDone.run();
            } catch (Exception e) {
                LOG.warning("onLaunchDone callback failed: " + e.getMessage());
            }
        }
    }

    // ── Status queries (always instant, never block) ──

    /**
     * Get launch progress for a specific container.
     */
    public ServiceProgress getLaunchProgress(String name) {
        var progress = launchProgressMap.get(name);
        if (progress == null) {
            return ServiceProgress.idle(name);
        }
        return progress.toServiceProgress();
    }

    /**
     * Get all active launch progresses (for dashboard display).
     */
    public List<ServiceProgress> getAllActiveLaunches() {
        var result = new ArrayList<ServiceProgress>();
        for (var entry : launchProgressMap.entrySet()) {
            var p = entry.getValue();
            if (!p.isDone()) {
                result.add(p.toServiceProgress());
            }
        }
        return result;
    }

    /**
     * Check if a launch is in progress for the given name.
     */
    public boolean isLaunching(String name) {
        return activeLaunches.containsKey(name);
    }

    // ── Service operations (start/stop systemd units inside containers) ──

    /**
     * Start a systemd unit inside a container.
     * Called via tell() from ContainerCommandConsumer — does not block the caller.
     */
    public void serviceStart(String container, String remote, String unit) {
        boolean ok = containerManager.serviceStart(container, remote, unit);
        if (ok) {
            LOG.info("Started service " + unit + " in container " + container);
        } else {
            LOG.warning("Failed to start service " + unit + " in container " + container);
        }
    }

    /**
     * Stop a systemd unit inside a container.
     * Called via tell() from ContainerCommandConsumer — does not block the caller.
     */
    public void serviceStop(String container, String remote, String unit) {
        boolean ok = containerManager.serviceStop(container, remote, unit);
        if (ok) {
            LOG.info("Stopped service " + unit + " in container " + container);
        } else {
            LOG.warning("Failed to stop service " + unit + " in container " + container);
        }
    }

    // ── Container operations (delegate to ContainerManager directly) ──

    public List<ContainerInfo> listWorkerContainers(String remote) {
        return containerManager.list(remote);
    }

    public List<ContainerInfo> listAllContainers(String remote) {
        return containerManager.listAll(remote);
    }

    public List<ImageInfo> listImages() {
        return containerManager.listImages();
    }

    /**
     * Stop a container: transitions to STOPPING, then executes stop+delete asynchronously.
     * For FAILED containers, performs immediate cleanup.
     */
    public boolean stopAndDeleteContainer(String name, String remote,
                                          ActorRef<ContainerSupervisorActor> self) {
        ContainerRecord record = containerRecords.get(name);
        if (record != null) {
            try {
                containerRecords.put(name, record.withState(ContainerLifecycleState.STOPPING));
            } catch (IllegalStateException e) {
                LOG.warning("Could not transition " + name + " to STOPPING: " + e.getMessage());
            }
        }

        // Execute stop+delete (blocking call — should be called via tell())
        boolean result = containerManager.stopAndDelete(name, remote);

        // Transition to STOPPED and clean up
        containerRecords.remove(name);
        launchProgressMap.remove(name);

        LOG.info("Container stopped and deleted: " + name + " result=" + result);
        return result;
    }

    /**
     * Synchronous stop for backward compatibility (called from REST API).
     */
    public boolean stopAndDeleteContainer(String name, String remote) {
        return stopAndDeleteContainer(name, remote, null);
    }

    // ── Container record access ──

    /**
     * Get the lifecycle record for a container.
     */
    public ContainerRecord getContainerRecord(String name) {
        return containerRecords.get(name);
    }

    /**
     * Get all container records (for dashboard and timeout manager).
     */
    public Map<String, ContainerRecord> getAllContainerRecords() {
        return Map.copyOf(containerRecords);
    }

    /**
     * Register a container record (used by state restoration on startup).
     */
    public void registerContainerRecord(ContainerRecord record) {
        containerRecords.put(record.getName(), record);
    }

    /**
     * Record user activity for a container (resets idle timer).
     */
    public void recordActivity(String name) {
        ContainerRecord record = containerRecords.get(name);
        if (record != null) {
            containerRecords.put(name, record.withActivity());
        }
    }
}
