package com.scivicslab.aiworkspace.restart;

import com.scivicslab.aiworkspace.build.BuildJobActor;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Build and restart" restarts a tool's instances only after its build succeeded, and tells the
 * page which restart job to follow, or why there is none.
 */
class BuildThenRestartTest {

    private final ActorSystem system = new ActorSystem("build-then-restart-test");
    /** The tools a restart was started for, in order. */
    private final List<String> restarted = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void tearDown() {
        system.terminate();
    }

    private ActorRef<BuildJobActor> job(String id) {
        return system.actorOf("job-" + id, new BuildJobActor(id, "html-saurus"));
    }

    /** Starts a restart job, as RestartAllService.start does, and records it. */
    private Optional<ActorRef<BuildJobActor>> startRestart(String tool) {
        restarted.add(tool);
        return Optional.of(job("restart-1"));
    }

    private static void waitUntil(Supplier<Boolean> done) throws InterruptedException {
        for (int i = 0; i < 100 && !done.get(); i++) Thread.sleep(20);
    }

    @Test
    @DisplayName("a build that succeeds is followed by a restart, whose job the build records")
    void restartsAfterASuccessfulBuild() throws Exception {
        ActorRef<BuildJobActor> build = job("build-1");
        BuildThenRestart.restartAfter(build, "html-saurus", this::startRestart);
        assertThat(restarted).as("nothing is restarted while the build runs").isEmpty();

        build.tell(BuildJobActor::succeeded).join();
        waitUntil(() -> build.ask(BuildJobActor::nextJobId).join() != null);

        assertThat(restarted).containsExactly("html-saurus");
        assertThat(build.ask(BuildJobActor::nextJobId).join()).isEqualTo("restart-1");
        assertThat(build.ask(BuildJobActor::nextSkipped).join()).isNull();
    }

    @Test
    @DisplayName("a build that fails restarts nothing, and the build says why")
    void restartsNothingAfterAFailedBuild() throws Exception {
        ActorRef<BuildJobActor> build = job("build-2");
        BuildThenRestart.restartAfter(build, "html-saurus", this::startRestart);

        build.tell(j -> j.failed("mvn install exited with 1")).join();
        waitUntil(() -> build.ask(BuildJobActor::nextSkipped).join() != null);

        assertThat(restarted).isEmpty();
        assertThat(build.ask(BuildJobActor::nextJobId).join()).isNull();
        assertThat(build.ask(BuildJobActor::nextSkipped).join()).contains("build failed");
    }

    @Test
    @DisplayName("a restart already under way is not started twice, and the build says so")
    void reportsARestartAlreadyUnderWay() throws Exception {
        ActorRef<BuildJobActor> build = job("build-3");
        BuildThenRestart.restartAfter(build, "html-saurus", tool -> Optional.empty());

        build.tell(BuildJobActor::succeeded).join();
        waitUntil(() -> build.ask(BuildJobActor::nextSkipped).join() != null);

        assertThat(build.ask(BuildJobActor::nextJobId).join()).isNull();
        assertThat(build.ask(BuildJobActor::nextSkipped).join()).contains("already being restarted");
    }
}
