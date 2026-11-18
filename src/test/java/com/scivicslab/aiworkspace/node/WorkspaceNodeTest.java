package com.scivicslab.aiworkspace.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.aiworkspace.backend.jvm.InstanceTree;
import com.scivicslab.aiworkspace.backend.jvm.ProcessSupervisor;
import com.scivicslab.aiworkspace.config.AiWorkspaceConfig;
import com.scivicslab.aiworkspace.model.DashboardModel;
import com.scivicslab.aiworkspace.model.SessionState;
import com.scivicslab.aiworkspace.model.SessionView;
import com.scivicslab.aiworkspace.rest.ServiceDirectoryResource;
import com.scivicslab.aiworkspace.spi.ServiceBackend;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.pojoactor.distributed.DistributedIIActorSystem;
import com.scivicslab.pojoactor.distributed.NodeInfo;
import com.scivicslab.pojoactor.distributed.discovery.StaticNodeDiscovery;

/**
 * AI-workspace's own actor system is a node of the distributed actor system, and other nodes may call only the
 * read actions of {@code ai-workspace-remote} (ActorNodesForWorkspaceAndPortal_261118_oo01, step 2; decisions 1, 2).
 *
 * <p>Two real {@code DistributedIIActorSystem}s talk over HTTP on 127.0.0.1: AI-workspace's, with the gateway and an
 * {@code instances} tree beside it as in the portal, and a caller's.</p>
 */
class WorkspaceNodeTest {

    private static final String NODE = "ai-workspace-test";
    private static final ObjectMapper JSON = new ObjectMapper();

    private DistributedIIActorSystem workspace;
    private DistributedIIActorSystem caller;
    private int actorPort;

    /** A backend with two running instances and one stopped, and two catalog tools. */
    private static final class Fixed implements ServiceBackend {
        private static SessionView session(String tool, int port, SessionState state, String title) {
            return new SessionView(tool, port, tool, "", state, "http://localhost:" + port + "/",
                    title == null ? Map.of() : Map.of("title", title), "", List.of(), null, null);
        }
        @Override public DashboardModel getDashboardModel() {
            return new DashboardModel(List.of(), List.of(
                    session("html-saurus", 28001, SessionState.READY, null),
                    session("chat-ui-with-audit-trail-cloud-llm", 28010, SessionState.READY, "dev"),
                    session("frame-viewer", 28011, SessionState.STARTING, null)), List.of());
        }
        @Override public void initialize(AiWorkspaceConfig config) {}
        @Override public void startService(String toolName, Map<String, String> params) {}
        @Override public void stopService(String toolName, int port) {}
        @Override public void restartAll(String toolName, Consumer<String> progress) {}
        @Override public List<String> getServiceLogs(String toolName, int port, int lines) { return List.of(); }
        @Override public String getBackendType() { return "fixed"; }
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
    }

    @BeforeEach
    void setUp() throws IOException {
        actorPort = freePort();
        workspace = WorkspaceNode.buildSystem(NODE, actorPort);
        // The portal's own tree lives in the same system: what the gateway must not let through.
        InstanceTree instances = new InstanceTree(workspace);
        instances.add(ProcessSupervisor.adopt(new AiWorkspaceConfig.ToolDefinition("html-saurus", "html-saurus.jar",
                28001, false, false, false, List.of(), List.of(), List.of(), null), 28001, ProcessHandle.current().pid()));
        workspace.actorOf("build-registry", new Object());

        ServiceBackend backend = new Fixed();
        WorkspaceNode.open(workspace, new WorkspaceGateway(NODE, actorPort,
                q -> ServiceDirectoryResource.list(backend, q.tool(), q.readyOnly()),
                () -> List.of(new WorkspaceGateway.CatalogEntry("html-saurus", "html-saurus", "installed", "2.6.0", "2.6.0", ""))),
                "127.0.0.1");

        NodeInfo me = new NodeInfo("caller", "127.0.0.1", freePort());
        caller = DistributedIIActorSystem.builder().discovery(new StaticNodeDiscovery(me, List.of())).noDeduplication().build();
        caller.registerRemoteNode(NODE, "127.0.0.1", actorPort);
    }

    @AfterEach
    void tearDown() {
        if (caller != null) caller.close();
        if (workspace != null) workspace.close();
    }

    private ActionResult call(String actor, String action, String args) {
        return caller.getRemoteActor(NODE, actor).callByActionName(action, args, Duration.ofSeconds(5));
    }

    @Test
    void listInstancesAnswersWhatGetApiServicesAnswers() throws Exception {
        ActionResult all = call(WorkspaceNode.GATEWAY, "listInstances", "{}");
        assertThat(all.isSuccess()).as(all.getResult()).isTrue();
        assertThat(JSON.readTree(all.getResult()))
                .isEqualTo(JSON.readTree(JSON.writeValueAsString(ServiceDirectoryResource.list(new Fixed(), null, false))));
        assertThat(JSON.readTree(all.getResult()).size()).isEqualTo(3);
        assertThat(JSON.readTree(all.getResult()).get(1).get("title").asText()).isEqualTo("dev");

        ActionResult ready = call(WorkspaceNode.GATEWAY, "listInstances", "{\"tool\":\"html-saurus\",\"readyOnly\":true}");
        assertThat(JSON.readTree(ready.getResult()))
                .isEqualTo(JSON.readTree(JSON.writeValueAsString(ServiceDirectoryResource.list(new Fixed(), "html-saurus", true))));
        assertThat(JSON.readTree(ready.getResult()).size()).isEqualTo(1);
        assertThat(JSON.readTree(ready.getResult()).get(0).get("port").asInt()).isEqualTo(28001);
    }

    @Test
    void listNodesAnswersThisNode() throws Exception {
        ActionResult nodes = call(WorkspaceNode.GATEWAY, "listNodes", "{}");
        assertThat(nodes.isSuccess()).as(nodes.getResult()).isTrue();
        assertThat(JSON.readTree(nodes.getResult())).isEqualTo(JSON.readTree(
                "[{\"name\":\"" + NODE + "\",\"host\":\"127.0.0.1\",\"actorPort\":" + actorPort + ",\"kind\":\"ai-workspace\"}]"));
    }

    @Test
    void listCatalogAnswersTheCatalog() throws Exception {
        ActionResult catalog = call(WorkspaceNode.GATEWAY, "listCatalog", "{}");
        assertThat(catalog.isSuccess()).as(catalog.getResult()).isTrue();
        assertThat(JSON.readTree(catalog.getResult()).get(0).get("name").asText()).isEqualTo("html-saurus");
        assertThat(JSON.readTree(catalog.getResult()).get(0).get("latestRelease").asText()).isEqualTo("2.6.0");
    }

    @Test
    void theRestOfTheTreeIsRefusedEvenByName() {
        assertThat(call("instances", "getNamesOfChildren", "{}").isSuccess()).isFalse();
        assertThat(call("instance-html-saurus-28001", "stop", "{}").isSuccess()).isFalse();
        assertThat(call("build-registry", "put", "{}").isSuccess()).isFalse();
        assertThat(call(WorkspaceNode.GATEWAY, "launch", "{\"tool\":\"html-saurus\"}").isSuccess())
                .as("an action that is not on the allow-list").isFalse();
        assertThat(workspace.hasActor("instance-html-saurus-28001")).isTrue();
    }

    @Test
    void theReceiverListensOnLoopbackOnly() throws Exception {
        Optional<InetAddress> outside = Collections.list(NetworkInterface.getNetworkInterfaces()).stream()
                .filter(i -> { try { return i.isUp() && !i.isLoopback(); } catch (IOException e) { return false; } })
                .flatMap(i -> Collections.list(i.getInetAddresses()).stream())
                .filter(a -> a instanceof Inet4Address && !a.isLoopbackAddress()).findFirst();
        org.junit.jupiter.api.Assumptions.assumeTrue(outside.isPresent(), "no non-loopback address on this machine");
        boolean connected;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(outside.get(), actorPort), 1000);
            connected = true;
        } catch (IOException e) {
            connected = false;
        }
        assertThat(connected).as("reachable on " + outside.get()).isFalse();
    }
}
