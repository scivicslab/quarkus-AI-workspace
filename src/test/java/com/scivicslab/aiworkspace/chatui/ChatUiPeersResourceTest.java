package com.scivicslab.aiworkspace.chatui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.aiworkspace.config.AiWorkspaceConfig;
import com.scivicslab.aiworkspace.model.DashboardModel;
import com.scivicslab.aiworkspace.model.SessionState;
import com.scivicslab.aiworkspace.spi.ServiceBackend;

/**
 * GET /api/chat-ui/peers lists the chat-ui-with-audit-trail instances that are up, in port order,
 * and leaves out the ones that are stopped, starting or failed (ChatUiPeers_261116_oo01).
 */
class ChatUiPeersResourceTest {

    private static List<String> launched(int port) {
        return List.of("java", "-Xmx2g", "-Dchat-ui.node-name=chat-ui-" + port,
                "-Dchat-ui.actor-port=" + (port + 1000), "-Dchat-ui.workspace-url=http://localhost:28000",
                "-Dquarkus.http.port=" + port, "-jar", "/home/u/works/chat-ui-with-audit-trail.jar");
    }

    /** A backend that reports a fixed set of chat-ui instances. */
    private static final class Fixed implements ServiceBackend {
        private final List<ChatUiPeers.Instance> instances;
        Fixed(List<ChatUiPeers.Instance> instances) { this.instances = instances; }
        @Override public List<ChatUiPeers.Instance> chatUiInstances() { return instances; }
        @Override public void initialize(AiWorkspaceConfig config) {}
        @Override public void startService(String toolName, Map<String, String> params) {}
        @Override public void stopService(String toolName, int port) {}
        @Override public void restartAll(String toolName, Consumer<String> progress) {}
        @Override public List<String> getServiceLogs(String toolName, int port, int lines) { return List.of(); }
        @Override public DashboardModel getDashboardModel() { return null; }
        @Override public String getBackendType() { return "fixed"; }
    }

    @Test
    void listsTheInstancesThatAreUpInPortOrder_andLeavesOutTheOthers() throws Exception {
        ChatUiPeersResource resource = new ChatUiPeersResource();
        resource.backend = new Fixed(List.of(
                new ChatUiPeers.Instance(28031, SessionState.READY, launched(28031)),
                new ChatUiPeers.Instance(28020, SessionState.STOPPED, launched(28020)),
                new ChatUiPeers.Instance(28010, SessionState.READY, launched(28010)),
                new ChatUiPeers.Instance(28032, SessionState.STARTING, launched(28032)),
                new ChatUiPeers.Instance(28033, SessionState.FAILED, launched(28033)),
                // started before this portal gave the options: it has no actor port to be reached at
                new ChatUiPeers.Instance(28034, SessionState.READY,
                        List.of("java", "-Dquarkus.http.port=28034", "-jar", "chat-ui-with-audit-trail.jar"))));

        String json = new ObjectMapper().writeValueAsString(resource.peers());

        System.out.println("GET /api/chat-ui/peers -> " + json);
        assertThat(json).isEqualTo("[{\"name\":\"chat-ui-28010\",\"host\":\"127.0.0.1\",\"actorPort\":29010},"
                + "{\"name\":\"chat-ui-28031\",\"host\":\"127.0.0.1\",\"actorPort\":29031}]");
    }

    @Test
    void anInstanceLaunchedWithItsOwnActorPortIsListedWithThatPort() throws Exception {
        ChatUiPeersResource resource = new ChatUiPeersResource();
        resource.backend = new Fixed(List.of(new ChatUiPeers.Instance(28040, SessionState.READY,
                List.of("java", "-Dchat-ui.actor-port=30500", "-Dchat-ui.node-name=gateway",
                        "-Dquarkus.http.port=28040", "-jar", "chat-ui-with-audit-trail.jar"))));

        assertThat(new ObjectMapper().writeValueAsString(resource.peers()))
                .isEqualTo("[{\"name\":\"gateway\",\"host\":\"127.0.0.1\",\"actorPort\":30500}]");
    }

    @Test
    void noInstanceGivesAnEmptyList() throws Exception {
        ChatUiPeersResource resource = new ChatUiPeersResource();
        resource.backend = new Fixed(List.of());
        assertThat(new ObjectMapper().writeValueAsString(resource.peers())).isEqualTo("[]");
    }
}
