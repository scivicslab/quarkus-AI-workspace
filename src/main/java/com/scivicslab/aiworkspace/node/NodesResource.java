package com.scivicslab.aiworkspace.node;

import java.util.List;
import java.util.function.Supplier;

import com.scivicslab.aiworkspace.actor.AiWorkspaceActorSystem;
import com.scivicslab.pojoactor.distributed.DistributedIIActorSystem;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * {@code GET /api/nodes}: the nodes of the distributed actor system this AI-workspace knows, each with its kind
 * (ActorNodesForWorkspaceAndPortal_261118_oo01, decision 3). {@code GET /api/chat-ui/peers} stays as it is: chat-ui
 * takes every entry of that list as a chat-ui to send requests to.
 */
@Path("/api/nodes")
@Produces(MediaType.APPLICATION_JSON)
public class NodesResource {

    @Inject
    AiWorkspaceActorSystem actors;

    /** The node's actor system; a test sets it directly. */
    Supplier<DistributedIIActorSystem> system = () -> actors.actorSystem();

    /** @return e.g. {@code [{"name":"ai-workspace-28000","host":"127.0.0.1","actorPort":29000,"kind":"ai-workspace"}]} */
    @GET
    public List<WorkspaceGateway.NodeEntry> nodes() {
        return WorkspaceNode.nodes(system.get());
    }
}
