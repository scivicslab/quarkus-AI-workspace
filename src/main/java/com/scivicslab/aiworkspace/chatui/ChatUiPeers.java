package com.scivicslab.aiworkspace.chatui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.scivicslab.aiworkspace.model.SessionState;

/**
 * The chat-ui-with-audit-trail instances this portal runs, as peers of one another
 * (ChatUiPeers_261116_oo01).
 *
 * <p>Each instance answers cross-instance requests on an actor port of its own and is known to the
 * others by a node name. This portal launches them, so it gives each one both at launch, together
 * with its own address; an instance then asks {@code GET /api/chat-ui/peers} for the others. The
 * list is read from the command line each instance actually runs with, so it says where an instance
 * listens, not where it would listen if it were launched now.</p>
 */
public final class ChatUiPeers {

    /** The jar every chat-ui-with-audit-trail tile runs; what makes a tile a chat-ui. */
    public static final String JAR = "chat-ui-with-audit-trail.jar";
    /** The actor port is the HTTP port plus this, so that the two never collide inside the tool port pool. */
    public static final int ACTOR_PORT_OFFSET = 1000;
    /** Every instance runs on this machine, as a child process of this portal. */
    public static final String HOST = "127.0.0.1";

    static final String NODE_NAME = "chat-ui.node-name";
    static final String ACTOR_PORT = "chat-ui.actor-port";
    static final String WORKSPACE_URL = "chat-ui.workspace-url";

    /** One peer, as GET /api/chat-ui/peers lists it. Registered so that Jackson can write it in the native image. */
    @io.quarkus.runtime.annotations.RegisterForReflection
    public record Peer(String name, String host, int actorPort) {}

    /** One chat-ui instance this portal runs: its HTTP port, its state and the command line it runs with. */
    public record Instance(int httpPort, SessionState state, List<String> commandLine) {}

    private ChatUiPeers() {}

    /** @return whether {@code jar} (a file name or a path, as a registry entry names it) is chat-ui-with-audit-trail */
    public static boolean isChatUi(String jar) {
        if (jar == null || jar.isBlank()) return false;
        Path name = Path.of(jar).getFileName();
        return name != null && name.toString().equals(JAR);
    }

    /**
     * The JVM options to add when launching a chat-ui on {@code httpPort}: the node name, the actor
     * port and this portal's URL, each one only when {@code given} does not already set it, so a
     * tile that names its own actor port or node name keeps it.
     *
     * @param given        the command built so far
     * @param httpPort     the instance's HTTP port
     * @param workspaceUrl this portal's base URL, e.g. {@code http://localhost:28000}
     * @return the options to add, in that order
     */
    public static List<String> launchOptions(List<String> given, int httpPort, String workspaceUrl) {
        List<String> out = new ArrayList<>();
        if (property(given, NODE_NAME).isEmpty()) out.add("-D" + NODE_NAME + "=chat-ui-" + httpPort);
        if (property(given, ACTOR_PORT).isEmpty()) out.add("-D" + ACTOR_PORT + "=" + (httpPort + ACTOR_PORT_OFFSET));
        if (property(given, WORKSPACE_URL).isEmpty()) out.add("-D" + WORKSPACE_URL + "=" + workspaceUrl);
        return out;
    }

    /**
     * The peers among {@code instances}: those that are up and run with an actor port, in HTTP port
     * order. An instance that is stopped, starting or failed cannot be reached and is left out, and so
     * is one started without an actor port (by a portal from before this list existed).
     */
    public static List<Peer> peers(List<Instance> instances) {
        return instances.stream()
                .filter(i -> i.state() == SessionState.READY)
                .sorted(Comparator.comparingInt(Instance::httpPort))
                .map(i -> peerOf(i.commandLine(), i.httpPort()))
                .flatMap(Optional::stream)
                .toList();
    }

    /** The peer an instance running {@code commandLine} is, or empty when it has no actor port. */
    static Optional<Peer> peerOf(List<String> commandLine, int httpPort) {
        Optional<Integer> actorPort = property(commandLine, ACTOR_PORT).flatMap(ChatUiPeers::port);
        if (actorPort.isEmpty()) return Optional.empty();
        String name = property(commandLine, NODE_NAME).filter(s -> !s.isBlank()).orElse("chat-ui-" + httpPort);
        return Optional.of(new Peer(name, HOST, actorPort.get()));
    }

    /** The value of {@code -D<key>=} in {@code args}; the last one, as the JVM takes it. */
    static Optional<String> property(List<String> args, String key) {
        if (args == null) return Optional.empty();
        String prefix = "-D" + key + "=";
        String found = null;
        for (String a : args) {
            if (a == null) continue;
            if (a.equals("-jar")) break;      // what follows the jar is the program's own arguments
            if (a.startsWith(prefix)) found = a.substring(prefix.length());
        }
        return Optional.ofNullable(found);
    }

    private static Optional<Integer> port(String s) {
        try {
            int p = Integer.parseInt(s.trim());
            return p >= 1 && p <= 65535 ? Optional.of(p) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
