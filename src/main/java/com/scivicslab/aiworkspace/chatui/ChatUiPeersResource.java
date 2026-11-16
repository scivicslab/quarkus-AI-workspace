package com.scivicslab.aiworkspace.chatui;

import java.util.List;

import com.scivicslab.aiworkspace.spi.ServiceBackend;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * {@code GET /api/chat-ui/peers}: the chat-ui-with-audit-trail instances this portal runs and sees
 * up, each with the node name and actor port another instance reaches it at
 * (ChatUiPeers_261116_oo01). It binds like the rest of the portal and needs no login: it holds only
 * names and port numbers on this machine.
 */
@Path("/api/chat-ui/peers")
@Produces(MediaType.APPLICATION_JSON)
public class ChatUiPeersResource {

    @Inject
    ServiceBackend backend;

    /** @return e.g. {@code [{"name":"chat-ui-28010","host":"127.0.0.1","actorPort":29010}]}, in port order */
    @GET
    public List<ChatUiPeers.Peer> peers() {
        return ChatUiPeers.peers(backend.chatUiInstances());
    }
}
