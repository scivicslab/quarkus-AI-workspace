package com.scivicslab.aiworkspace.restart;

import com.scivicslab.aiworkspace.build.BuildJobActor;
import com.scivicslab.pojoactor.core.ActorRef;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;
import java.util.function.Function;

/**
 * The Catalog's "Build and restart": restarts every running instance of a tool once its snapshot
 * build has succeeded, and restarts nothing when the build failed.
 *
 * <p>The server, not the page, starts the restart, so it follows the build even when the browser
 * that asked for it is closed during the minutes the build takes. The build and the restart are the
 * same jobs "Build snapshot" and "Restart all" start ({@code SnapshotBuildService},
 * {@link RestartAllService}); the build job records the restart's id ({@link BuildJobActor#next}),
 * or why there is none ({@link BuildJobActor#nextSkipped}), for the page to follow.</p>
 */
@ApplicationScoped
public class BuildThenRestart {

    @Inject
    RestartAllService restartAllService;

    /** Restarts {@code tool}'s instances when {@code build} succeeds. Returns at once. */
    public void restartAfter(ActorRef<BuildJobActor> build, String tool) {
        restartAfter(build, tool, restartAllService::start);
    }

    /**
     * As {@link #restartAfter(ActorRef, String)}, with the restart started by {@code startRestart}
     * ({@link RestartAllService#start} in the application; a stand-in in a test).
     */
    static void restartAfter(ActorRef<BuildJobActor> build, String tool,
                             Function<String, Optional<ActorRef<BuildJobActor>>> startRestart) {
        // The future completes inside the build job's own actor (succeeded() or failed()), so the
        // follow-up runs on a thread of its own: asking that actor from its own thread would wait
        // forever.
        build.ask(BuildJobActor::finished).join().thenAcceptAsync(state -> {
            if (state != BuildJobActor.State.SUCCESS) {
                build.tell(j -> j.nextSkipped("the build failed, so nothing was restarted"));
                return;
            }
            Optional<ActorRef<BuildJobActor>> restart = startRestart.apply(tool);
            if (restart.isEmpty()) {
                build.tell(j -> j.nextSkipped(tool + " is already being restarted"));
                return;
            }
            String restartId = restart.get().ask(BuildJobActor::id).join();
            build.tell(j -> j.next(restartId));
        }, r -> Thread.ofVirtual().name("build-then-restart-" + tool).start(r));
    }
}
