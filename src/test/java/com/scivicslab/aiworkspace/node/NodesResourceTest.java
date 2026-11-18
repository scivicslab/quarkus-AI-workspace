package com.scivicslab.aiworkspace.node;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.pojoactor.distributed.DistributedIIActorSystem;

/**
 * {@code GET /api/nodes} lists the nodes AI-workspace knows: for now, itself (ActorNodesForWorkspaceAndPortal_261118_oo01,
 * step 3; decision 3). {@code listNodes} on the gateway answers the same list.
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
        assertThat(new ObjectMapper().writeValueAsString(resource.nodes()))
                .isEqualTo("[{\"name\":\"ai-workspace-28000\",\"host\":\"127.0.0.1\",\"actorPort\":29000,\"kind\":\"ai-workspace\"}]");
    }

    @Test
    void theGatewayAnswersTheSameList() throws Exception {
        NodesResource resource = new NodesResource();
        resource.system = () -> system;
        WorkspaceGateway gateway = new WorkspaceGateway(() -> WorkspaceNode.nodes(system), q -> java.util.List.of(), java.util.List::of);
        assertThat(new ObjectMapper().readTree(gateway.listNodes("{}").getResult()))
                .isEqualTo(new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(resource.nodes())));
    }
}
