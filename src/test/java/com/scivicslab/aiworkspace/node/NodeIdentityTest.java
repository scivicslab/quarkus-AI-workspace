package com.scivicslab.aiworkspace.node;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The node name and actor port AI-workspace takes from its own HTTP port (ActorNodesForWorkspaceAndPortal_261118_oo01). */
class NodeIdentityTest {

    @AfterEach
    void tearDown() {
        System.clearProperty("ai-workspace.node-name");
        System.clearProperty("ai-workspace.actor-port");
    }

    @Test
    void byDefaultTheNameAndPortFollowTheHttpPort() {
        assertThat(NodeIdentity.name(28000)).isEqualTo("ai-workspace-28000");
        assertThat(NodeIdentity.actorPort(28000)).isEqualTo(29000);
    }

    @Test
    void systemPropertiesOverrideBoth() {
        System.setProperty("ai-workspace.node-name", "workspace-a");
        System.setProperty("ai-workspace.actor-port", "29500");
        assertThat(NodeIdentity.name(28000)).isEqualTo("workspace-a");
        assertThat(NodeIdentity.actorPort(28000)).isEqualTo(29500);
    }
}
