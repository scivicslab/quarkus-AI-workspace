package com.scivicslab.aiworkspace.backend.jvm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.scivicslab.aiworkspace.config.AiWorkspaceConfig;
import com.scivicslab.aiworkspace.model.SessionState;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

/**
 * The instances this portal has are held in one place: the children of the actor {@code instances}
 * (ActorNodesForWorkspaceAndPortal_261118_oo01, step 1). Nothing keeps a second list of their
 * references (TwoRegistriesForOneActorPopulation_260914_oo01, ActorSuffixAndOwnedActorRef_260722_oo01).
 *
 * <p>No process is started: a READY instance is an adopted supervisor of this test's own JVM, a STOPPED one
 * a supervisor that was never started.</p>
 */
class InstanceTreeTest {

    private ActorSystem system;
    private InstanceTree tree;

    @BeforeEach
    void setUp() {
        system = new ActorSystem("instance-tree-test");
        tree = new InstanceTree(system);
    }

    @AfterEach
    void tearDown() {
        system.terminate();
    }

    private static AiWorkspaceConfig.ToolDefinition tool(String name) {
        return new AiWorkspaceConfig.ToolDefinition(name, name + ".jar", 28010, false, false, false,
                List.of(), List.of(), List.of(), null);
    }

    private static ProcessSupervisor ready(String tool, int port) {
        return ProcessSupervisor.adopt(tool(tool), port, ProcessHandle.current().pid());
    }

    private static ProcessSupervisor stopped(String tool, int port) {
        return new ProcessSupervisor(tool(tool), port, Map.of());
    }

    private static List<Integer> ports(List<ActorRef<ProcessSupervisor>> refs) {
        return refs.stream().map(r -> r.ask(ProcessSupervisor::getPort).join()).sorted().toList();
    }

    @Test
    void everyInstanceActorIsAChildOfInstances() {
        ActorRef<ProcessSupervisor> a = tree.add(ready("html-saurus", 28001));
        ActorRef<ProcessSupervisor> b = tree.add(ready("chat-ui-with-audit-trail-local-llm", 28010));

        assertThat(a.getName()).isEqualTo("instance-html-saurus-28001");
        assertThat(b.getName()).isEqualTo("instance-chat-ui-with-audit-trail-local-llm-28010");
        ActorRef<?> root = system.getActor(InstanceTree.ROOT);
        assertThat(root.getNamesOfChildren()).containsExactlyInAnyOrder(a.getName(), b.getName());
        assertThat(a.getParentName()).isEqualTo(InstanceTree.ROOT);
        assertThat(b.getParentName()).isEqualTo(InstanceTree.ROOT);
    }

    @Test
    void forToolAndAllAreReadFromTheTree() {
        tree.add(ready("html-saurus", 28001));
        tree.add(ready("html-saurus", 28009));
        tree.add(ready("frame-viewer", 28011));

        assertThat(ports(tree.forTool("html-saurus"))).containsExactly(28001, 28009);
        assertThat(ports(tree.forTool("frame-viewer"))).containsExactly(28011);
        assertThat(tree.forTool("code-raptor")).isEmpty();
        assertThat(ports(tree.all())).containsExactly(28001, 28009, 28011);
    }

    @Test
    void droppingFinishedInstancesRemovesTheirActors() {
        tree.add(ready("html-saurus", 28001));
        ActorRef<ProcessSupervisor> finished = tree.add(stopped("html-saurus", 28009));

        List<ActorRef<ProcessSupervisor>> left = tree.dropFinished("html-saurus");

        assertThat(ports(left)).containsExactly(28001);
        assertThat(system.hasActor("instance-html-saurus-28009")).isFalse();
        assertThat(system.getActor(InstanceTree.ROOT).getNamesOfChildren())
                .containsExactly("instance-html-saurus-28001");
        assertThat(finished.isAlive()).as("a dropped instance's actor is closed, not left running").isFalse();
    }

    @Test
    void aNewInstanceOnThePortOfAFinishedOneReplacesIt() {
        ActorRef<ProcessSupervisor> before = tree.add(stopped("html-saurus", 28001));
        ActorRef<ProcessSupervisor> after = tree.add(ready("html-saurus", 28001));

        assertThat(before.isAlive()).isFalse();
        assertThat(after.isAlive()).isTrue();
        assertThat(tree.forTool("html-saurus")).containsExactly(after);
        assertThat(system.getActor(InstanceTree.ROOT).getNamesOfChildren())
                .containsExactly("instance-html-saurus-28001");
    }

    @Test
    void aLiveInstanceIsNeverReplaced() {
        ActorRef<ProcessSupervisor> live = tree.add(ready("html-saurus", 28001));

        assertThatThrownBy(() -> tree.add(ready("html-saurus", 28001)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("instance-html-saurus-28001");
        assertThat(live.isAlive()).isTrue();
        assertThat(live.ask(ProcessSupervisor::getState).join()).isEqualTo(SessionState.READY);
    }

    @Test
    void removingAToolsInstancesLeavesTheOtherTools() {
        tree.add(ready("html-saurus", 28001));
        tree.add(ready("frame-viewer", 28011));

        tree.removeAll("html-saurus");

        assertThat(tree.forTool("html-saurus")).isEmpty();
        assertThat(ports(tree.forTool("frame-viewer"))).containsExactly(28011);
        assertThat(system.hasActor("instance-html-saurus-28001")).isFalse();
    }

    @Test
    void theInstancesActorHoldsNoReferences() {
        assertThat(Instances.class.getDeclaredFields())
                .as("the parent of the instance actors keeps no list of them: the tree is the list")
                .isEmpty();
    }
}
