package com.scivicslab.aiworkspace.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.aiworkspace.backend.jvm.InstanceTree;
import com.scivicslab.aiworkspace.backend.jvm.ProcessSupervisor;
import com.scivicslab.aiworkspace.config.AiWorkspaceConfig;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.pojoactor.distributed.DistributedIIActorSystem;
import com.scivicslab.pojoactor.distributed.NodeInfo;
import com.scivicslab.pojoactor.distributed.discovery.StaticNodeDiscovery;

/**
 * A tool that AI-workspace runs announces itself with {@code announceNode} on {@code ai-workspace-remote}, and the node
 * list carries it while that instance is READY (ActorNodesForWorkspaceAndPortal_261118_oo01, step 4; question 11).
 *
 * <p>Two real {@code DistributedIIActorSystem}s talk over HTTP on 127.0.0.1. The instances are supervisors in the
 * portal's own tree: a READY one is an adopted supervisor of this test's JVM, a STOPPED one was never started.</p>
 */
class AnnounceNodeTest {

    private static final String NODE = "ai-workspace-test";
    private static final ObjectMapper JSON = new ObjectMapper();

    private DistributedIIActorSystem workspace;
    private DistributedIIActorSystem caller;
    private InstanceTree instances;
    private int actorPort;

    private static AiWorkspaceConfig.ToolDefinition tool(String name) {
        return new AiWorkspaceConfig.ToolDefinition(name, name + ".jar", 0, false, false, false,
                List.of(), List.of(), List.of(), null);
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
    }

    @BeforeEach
    void setUp() throws IOException {
        actorPort = freePort();
        workspace = WorkspaceNode.buildSystem(NODE, actorPort);
        instances = new InstanceTree(workspace);
        instances.add(ProcessSupervisor.adopt(tool("html-saurus"), 18001, ProcessHandle.current().pid()));
        instances.add(ProcessSupervisor.adopt(tool("html-saurus"), 18009, ProcessHandle.current().pid()));
        instances.add(new ProcessSupervisor(tool("frame-viewer"), 18011, Map.of()));

        WorkspaceNode.open(workspace, new WorkspaceGateway(
                () -> WorkspaceNode.nodes(workspace, instances.announcedNodes()),
                q -> List.of(), List::of,
                WorkspaceNode.announcer(workspace, instances::announce)), "127.0.0.1");

        NodeInfo me = new NodeInfo("caller", "127.0.0.1", freePort());
        caller = DistributedIIActorSystem.builder().discovery(new StaticNodeDiscovery(me, List.of())).noDeduplication().build();
        caller.registerRemoteNode(NODE, "127.0.0.1", actorPort);
    }

    @AfterEach
    void tearDown() {
        if (caller != null) caller.close();
        if (workspace != null) workspace.close();
    }

    private ActionResult call(String action, String args) {
        return caller.getRemoteActor(NODE, WorkspaceNode.GATEWAY).callByActionName(action, args, Duration.ofSeconds(5));
    }

    private JsonNode announce(String name, String kind, int actorPort, int httpPort) throws Exception {
        ActionResult r = call("announceNode", "{\"name\":\"" + name + "\",\"kind\":\"" + kind + "\",\"actorPort\":"
                + actorPort + ",\"httpPort\":" + httpPort + "}");
        assertThat(r.isSuccess()).as(r.getResult()).isTrue();
        return JSON.readTree(r.getResult());
    }

    private JsonNode listNodes() throws Exception {
        ActionResult r = call("listNodes", "{}");
        assertThat(r.isSuccess()).as(r.getResult()).isTrue();
        return JSON.readTree(r.getResult());
    }

    private String self() {
        return "{\"name\":\"" + NODE + "\",\"host\":\"127.0.0.1\",\"actorPort\":" + actorPort + ",\"kind\":\"ai-workspace\"}";
    }

    @Test
    void announceNodeIsTheOneWriteOnTheAllowList() {
        assertThat(WorkspaceNode.ACTIONS).containsExactlyInAnyOrder("listInstances", "listCatalog", "listNodes", "announceNode");
        assertThat(call("stopInstance", "{\"port\":18001}").isSuccess()).as("not on the allow-list").isFalse();
    }

    @Test
    void aReadyInstanceThatAnnouncesItselfIsListedInPortOrder() throws Exception {
        assertThat(announce("html-saurus-18009", "html-saurus", 19009, 18009).get("accepted").asBoolean()).isTrue();
        assertThat(announce("html-saurus-18001", "html-saurus", 19001, 18001).get("accepted").asBoolean()).isTrue();
        assertThat(listNodes()).isEqualTo(JSON.readTree("[" + self()
                + ",{\"name\":\"html-saurus-18001\",\"host\":\"127.0.0.1\",\"actorPort\":19001,\"kind\":\"html-saurus\"}"
                + ",{\"name\":\"html-saurus-18009\",\"host\":\"127.0.0.1\",\"actorPort\":19009,\"kind\":\"html-saurus\"}]"));
    }

    @Test
    void announcingAgainGivesTheSameList() throws Exception {
        announce("html-saurus-18001", "html-saurus", 19001, 18001);
        JsonNode once = listNodes();
        announce("html-saurus-18001", "html-saurus", 19001, 18001);
        assertThat(listNodes()).isEqualTo(once);
        assertThat(once.size()).isEqualTo(2);
    }

    @Test
    void aPortThisWorkspaceDoesNotRunIsRefused() throws Exception {
        JsonNode answer = announce("html-saurus-18999", "html-saurus", 19999, 18999);
        assertThat(answer.get("accepted").asBoolean()).isFalse();
        assertThat(answer.get("reason").asText()).contains("18999");
        assertThat(listNodes()).isEqualTo(JSON.readTree("[" + self() + "]"));
    }

    @Test
    void anInstanceThatIsNotReadyIsRefused() throws Exception {
        JsonNode answer = announce("frame-viewer-18011", "frame-viewer", 19011, 18011);
        assertThat(answer.get("accepted").asBoolean()).isFalse();
        assertThat(answer.get("reason").asText()).contains("STOPPED");
        assertThat(listNodes().size()).isEqualTo(1);
    }

    @Test
    void anInstanceThatLeavesTheTreeTakesItsAnnouncementWithIt() throws Exception {
        announce("html-saurus-18001", "html-saurus", 19001, 18001);
        assertThat(listNodes().size()).isEqualTo(2);
        instances.removeAll("html-saurus");
        assertThat(listNodes()).isEqualTo(JSON.readTree("[" + self() + "]"));
    }

    @Test
    void thisWorkspacesOwnNameIsRefused() throws Exception {
        JsonNode answer = announce(NODE, "ai-workspace", 19001, 18001);
        assertThat(answer.get("accepted").asBoolean()).isFalse();
        assertThat(listNodes().size()).isEqualTo(1);
    }

    @Test
    void aMalformedAnnouncementFails() throws Exception {
        assertThat(call("announceNode", "{\"kind\":\"html-saurus\",\"actorPort\":19001,\"httpPort\":18001}").isSuccess())
                .as("no name").isFalse();
        assertThat(call("announceNode", "{\"name\":\"x\",\"kind\":\"html-saurus\",\"actorPort\":70000,\"httpPort\":18001}").isSuccess())
                .as("not a port").isFalse();
        assertThat(call("announceNode", "{\"name\":\"a b\",\"kind\":\"html-saurus\",\"actorPort\":19001,\"httpPort\":18001}").isSuccess())
                .as("not a node name").isFalse();
        assertThat(listNodes().size()).isEqualTo(1);
    }
}
