package com.scivicslab.aiworkspace.node;

/**
 * The node name and actor port of this AI-workspace (ActorNodesForWorkspaceAndPortal_261118_oo01).
 *
 * <p>Taken from the portal's own HTTP port, the rule chat-ui uses: {@code ai-workspace-<HTTP port>}, and the HTTP port
 * plus {@value #ACTOR_PORT_OFFSET}. {@code -Dai-workspace.node-name} and {@code -Dai-workspace.actor-port} override
 * them.</p>
 */
public final class NodeIdentity {

    /** The actor port is the HTTP port plus this. */
    public static final int ACTOR_PORT_OFFSET = 1000;

    private NodeIdentity() {}

    /** @return the node name for a portal on {@code httpPort} */
    public static String name(int httpPort) {
        String given = System.getProperty("ai-workspace.node-name");
        return given != null && !given.isBlank() ? given.strip() : "ai-workspace-" + httpPort;
    }

    /** @return the actor port for a portal on {@code httpPort} */
    public static int actorPort(int httpPort) {
        String given = System.getProperty("ai-workspace.actor-port");
        if (given != null && !given.isBlank()) {
            try {
                return Integer.parseInt(given.strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("ai-workspace.actor-port is not a port number: " + given, e);
            }
        }
        return httpPort + ACTOR_PORT_OFFSET;
    }
}
