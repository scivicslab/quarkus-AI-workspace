package com.scivicslab.aiworkspace.backend.jvm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;

import com.scivicslab.aiworkspace.model.NodeAnnouncement;
import com.scivicslab.aiworkspace.model.SessionState;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

/**
 * The instances this portal has, held in one place: the children of the actor {@value #ROOT}
 * (ActorNodesForWorkspaceAndPortal_261118_oo01, step 1).
 *
 * <p>Each instance is one {@link ProcessSupervisor} actor named {@code instance-<tool>-<port>}, created with
 * {@code createChild} under {@value #ROOT}. Every question about instances (one tool's, all of them) is
 * answered from that set of children, and nothing keeps a second list of their references.</p>
 *
 * <p>What changes the children, and what reads them, runs as a message to {@value #ROOT}, one at a time,
 * so that "drop what has finished, then read what is left" sees one set throughout, as the single
 * messages of the registry it replaces did ({@code ActorBasedState_260907_oo01}). An instance that is
 * dropped is closed as well as forgotten, so its actor does not keep running after it leaves the set.</p>
 *
 * <p>Not itself an actor and not wrapped in one: it is the portal's handle on the tree.</p>
 */
public final class InstanceTree {

    /** The name of the parent of every instance actor. */
    public static final String ROOT = "instances";

    private static final String PREFIX = "instance-";

    private final ActorSystem system;
    private final ActorRef<Instances> root;

    /** Creates the actor {@value #ROOT} in {@code system}. */
    public InstanceTree(ActorSystem system) {
        this.system = system;
        this.root = system.actorOf(ROOT, new Instances());
    }

    /** The actor name of one instance. */
    static String nameOf(String tool, int port) {
        return PREFIX + tool + "-" + port;
    }

    /**
     * Wraps {@code supervisor} as a child of {@value #ROOT}, named after its tool and port.
     *
     * <p>A finished instance (stopped or failed) already on that port is closed and replaced: that is how a
     * tool is started again on the port it had. A live one (starting or ready) is never replaced.</p>
     *
     * @return the new instance's actor
     * @throws IllegalStateException when a live instance of that tool holds that port
     */
    public ActorRef<ProcessSupervisor> add(ProcessSupervisor supervisor) {
        String name = nameOf(supervisor.getToolName(), supervisor.getPort());
        return inRoot(() -> {
            ActorRef<ProcessSupervisor> old = system.getActor(name);
            if (old != null) {
                // Asked, not read directly: the answer comes after every message already queued to it.
                SessionState state = old.ask(ProcessSupervisor::getState).join();
                if (!finished(state)) {
                    throw new IllegalStateException(name + " is " + state + "; a live instance is not replaced");
                }
                forget(old);
            }
            return root.createChild(name, supervisor);
        });
    }

    /** That tool's instances, in any state, in name order; never null. */
    public List<ActorRef<ProcessSupervisor>> forTool(String tool) {
        return inRoot(() -> children(tool));
    }

    /** Every instance of every tool, in name order. */
    public List<ActorRef<ProcessSupervisor>> all() {
        return inRoot(() -> children(null));
    }

    /**
     * Closes and forgets that tool's instances that have stopped or failed, and returns what is left.
     *
     * <p>One message, not two: a new instance's port is chosen from the instances still there, and that
     * must be the set this dropping produced.</p>
     */
    public List<ActorRef<ProcessSupervisor>> dropFinished(String tool) {
        return inRoot(() -> {
            for (ActorRef<ProcessSupervisor> c : children(tool)) {
                if (finished(c.ask(ProcessSupervisor::getState).join())) forget(c);
            }
            return children(tool);
        });
    }

    /**
     * Closes and forgets every instance of that tool, whatever its state.
     *
     * <p>Each one is asked its state first, so a {@code stop} already sent to it has run before its actor is
     * closed; closing drops the messages still waiting.</p>
     */
    public void removeAll(String tool) {
        inRoot(() -> {
            for (ActorRef<ProcessSupervisor> c : children(tool)) {
                c.ask(ProcessSupervisor::getState).join();
                forget(c);
            }
            return null;
        });
    }

    /**
     * Gives an announcement to the instance it is about: the one on its HTTP port, which must be READY
     * (ActorNodesForWorkspaceAndPortal_261118_oo01, question 11).
     *
     * @return empty when the instance took it, or why it was refused
     */
    public Optional<String> announce(NodeAnnouncement announcement) {
        int port = announcement.httpPort();
        return inRoot(() -> {
            for (ActorRef<ProcessSupervisor> c : children(null)) {
                if (c.ask(ProcessSupervisor::getPort).join() != port) continue;
                SessionState state = c.ask(ProcessSupervisor::getState).join();
                if (state != SessionState.READY) {
                    return Optional.of("the instance on port " + port + " is " + state + ", not READY");
                }
                c.ask(s -> { s.announce(announcement); return null; }).join();
                return Optional.<String>empty();
            }
            return Optional.of("this AI-workspace runs no instance on port " + port);
        });
    }

    /** What the READY instances announced about themselves, in HTTP port order. */
    public List<NodeAnnouncement> announcedNodes() {
        return inRoot(() -> {
            List<NodeAnnouncement> out = new ArrayList<>();
            for (ActorRef<ProcessSupervisor> c : children(null)) {
                NodeAnnouncement a = c.ask(s -> s.getState() == SessionState.READY ? s.getAnnouncement() : null).join();
                if (a != null) out.add(a);
            }
            out.sort(Comparator.comparingInt(NodeAnnouncement::httpPort));
            return out;
        });
    }

    private List<ActorRef<ProcessSupervisor>> children(String toolOrNull) {
        String prefix = toolOrNull == null ? PREFIX : PREFIX + toolOrNull + "-";
        return root.getNamesOfChildren().stream()
                // instance-<tool>-<port>: the port is all digits, so "html-saurus" does not take "html-saurus-x"'s
                .filter(n -> n.startsWith(prefix) && (toolOrNull == null || n.substring(prefix.length()).matches("\\d+")))
                .sorted()
                .map(n -> system.<ProcessSupervisor>getActor(n))
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Closes one instance's actor and removes it from the tree. {@code close} takes the name out of the actor system
     * and, from pojo-actor 4.6.0, out of the parent's children too; the two removals after it make sure of both
     * whatever the version, and do nothing when {@code close} already did them.
     */
    private void forget(ActorRef<ProcessSupervisor> instance) {
        String name = instance.getName();
        instance.close();
        system.removeActor(name);
        root.getNamesOfChildren().remove(name);
    }

    private static boolean finished(SessionState state) {
        return state == SessionState.STOPPED || state == SessionState.FAILED;
    }

    /** Runs {@code work} as a message to {@value #ROOT} and answers its result, unwrapping its exception. */
    private <R> R inRoot(java.util.function.Supplier<R> work) {
        try {
            return root.ask(i -> work.get()).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            throw e;
        }
    }
}
