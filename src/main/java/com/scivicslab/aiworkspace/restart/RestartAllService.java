package com.scivicslab.aiworkspace.restart;

import com.scivicslab.aiworkspace.actor.AiWorkspaceActorSystem;
import com.scivicslab.aiworkspace.build.BuildJobActor;
import com.scivicslab.aiworkspace.spi.ServiceBackend;
import com.scivicslab.pojoactor.core.ActorRef;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Restarts every running instance of a tool in the background, and reports how far it got.
 *
 * <p>Restarting waits for each instance to stop and become ready again, which takes as long as the
 * tool takes to start, so it runs on a virtual thread and returns a job at once. The job is a
 * {@link BuildJobActor}, the same record a snapshot build keeps, so it is read through the same
 * {@code GET /api/tool/{name}/build-status/{jobId}} ({@code SnapshotBuildService#get}).</p>
 */
@ApplicationScoped
public class RestartAllService {

    @Inject
    ServiceBackend backend;

    @Inject
    AiWorkspaceActorSystem actors;

    /** Tools being restarted now: a second request for one of them is refused, not run alongside. */
    private final Set<String> restarting = new HashSet<>();

    /**
     * Starts restarting {@code toolName}'s instances.
     *
     * @return the job, already running; empty when that tool is already being restarted
     */
    public Optional<ActorRef<BuildJobActor>> start(String toolName) {
        synchronized (restarting) {
            if (!restarting.add(toolName)) {
                return Optional.empty();
            }
        }
        ActorRef<BuildJobActor> job = actors.newBuildJob(UUID.randomUUID().toString(), toolName);
        Thread.ofVirtual().name("restart-all-" + toolName).start(() -> run(job, toolName));
        return Optional.of(job);
    }

    private void run(ActorRef<BuildJobActor> job, String toolName) {
        try {
            job.tell(j -> j.step("restarting"));
            backend.restartAll(toolName, line -> {
                job.tell(j -> j.step(line));
                job.tell(j -> j.append(line));
            });
            job.tell(j -> j.step("done"));
            job.tell(j -> j.succeeded());
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            job.tell(j -> j.append("FAILED: " + message));
            job.tell(j -> j.failed(message));
        } finally {
            synchronized (restarting) {
                restarting.remove(toolName);
            }
        }
    }
}
