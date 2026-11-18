package com.scivicslab.aiworkspace.node;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.pojoactor.distributed.DistributedIIActorSystem;

/**
 * {@code GET /api/nodes} lists the nodes AI-workspace knows: itself, then the nodes its READY instances announced, in
 * port order (ActorNodesForWorkspaceAndPortal_261118_oo01, steps 3 and 4; decision 3). {@code listNodes} on the gateway
 * answers the same list.
 */
class NodesResourceTest {

    private DistributedIIActorSystem system;

    @BeforeEach
    void setUp() {
        system = WorkspaceNode.buildSystem("ai-workspace-28000", 29000);
    }

    @AfterEach
    void tearDown() {
        system.close();
    }

    @Test
    void listsThisNodeWithItsKind() throws Exception {
        NodesResource resource = new NodesResource();
        resource.system = () -> system;
        resource.announced = java.util.List::of;
        assertThat(new ObjectMapper().writeValueAsString(resource.nodes()))
                .isEqualTo("[{\"name\":\"ai-workspace-28000\",\"host\":\"127.0.0.1\",\"actorPort\":29000,\"kind\":\"ai-workspace\"}]");
    }

    @Test
    void listsTheAnnouncedNodesAfterThisOne() throws Exception {
        NodesResource resource = new NodesResource();
        resource.system = () -> system;
        resource.announced = () -> java.util.List.of(
                new com.scivicslab.aiworkspace.model.NodeAnnouncement("html-saurus-28001", "html-saurus", 29001, 28001));
        assertThat(new ObjectMapper().writeValueAsString(resource.nodes()))
                .isEqualTo("[{\"name\":\"ai-workspace-28000\",\"host\":\"127.0.0.1\",\"actorPort\":29000,\"kind\":\"ai-workspace\"},"
                        + "{\"name\":\"html-saurus-28001\",\"host\":\"127.0.0.1\",\"actorPort\":29001,\"kind\":\"html-saurus\"}]");
    }

    @Test
    void theGatewayAnswersTheSameList() throws Exception {
        NodesResource resource = new NodesResource();
        resource.system = () -> system;
        resource.announced = java.util.List::of;
        WorkspaceGateway gateway = new WorkspaceGateway(() -> WorkspaceNode.nodes(system, java.util.List.of()),
                q -> java.util.List.of(), java.util.List::of, a -> java.util.Optional.empty());
        assertThat(new ObjectMapper().readTree(gateway.listNodes("{}").getResult()))
                .isEqualTo(new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(resource.nodes())));
    }
}
