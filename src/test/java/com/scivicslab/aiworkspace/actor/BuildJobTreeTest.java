package com.scivicslab.aiworkspace.actor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.scivicslab.aiworkspace.build.BuildJobActor;
import com.scivicslab.aiworkspace.build.Builds;
import com.scivicslab.pojoactor.core.ActorRef;

/**
 * The snapshot builds this portal started are held in one place: the children of the actor {@code build-registry}.
 * Nothing keeps a second map of their references (TwoRegistriesForOneActorPopulation_260914_oo01,
 * ActorSuffixAndOwnedActorRef_260722_oo01), and finding one never creates one (LookupThatCreates_260914_oo01).
 */
class BuildJobTreeTest {

    private AiWorkspaceActorSystem actors;

    @BeforeEach
    void setUp() {
        actors = new AiWorkspaceActorSystem();
        actors.init();
    }

    @AfterEach
    void tearDown() {
        actors.shutdown();
    }

    @Test
    void aBuildIsAChildOfTheRegistryAndIsFoundThere() {
        ActorRef<BuildJobActor> job = actors.newBuildJob("j1", "html-saurus");

        assertThat(job.getName()).isEqualTo("build-j1");
        assertThat(job.getParentName()).isEqualTo("build-registry");
        assertThat(actors.buildRegistry().getNamesOfChildren()).containsExactly("build-j1");
        assertThat(actors.buildJob("j1")).containsSame(job);
    }

    @Test
    void anUnknownBuildIsNotFoundAndNothingIsCreated() {
        actors.newBuildJob("j1", "html-saurus");

        assertThat(actors.buildJob("j2")).isEmpty();
        assertThat(actors.actorSystem().hasActor("build-j2")).isFalse();
        assertThat(actors.buildRegistry().getNamesOfChildren()).containsExactly("build-j1");
    }

    @Test
    void anActorWithABuildsNameThatIsNotItsChildIsNotABuild() {
        actors.actorSystem().actorOf("build-j9", new Object());

        assertThat(actors.buildJob("j9")).isEmpty();
    }

    @Test
    void theRegistryHoldsNoReferences() {
        assertThat(Builds.class.getDeclaredFields())
                .as("the parent of the build actors keeps no map of them: the tree is the map")
                .isEmpty();
    }
}
