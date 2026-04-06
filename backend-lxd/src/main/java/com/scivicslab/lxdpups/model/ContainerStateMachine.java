package com.scivicslab.lxdpups.model;

import java.util.Map;
import java.util.Set;

/**
 * Validates state transitions for the 6-state lifecycle model.
 *
 * This class is stateless and all methods are static.
 * It defines which transitions are allowed and rejects all others.
 */
public final class ContainerStateMachine {

    private static final Map<ContainerLifecycleState, Set<ContainerLifecycleState>> TRANSITIONS = Map.of(
            ContainerLifecycleState.CREATING, Set.of(
                    ContainerLifecycleState.STARTING,
                    ContainerLifecycleState.FAILED),
            ContainerLifecycleState.STARTING, Set.of(
                    ContainerLifecycleState.READY,
                    ContainerLifecycleState.FAILED),
            ContainerLifecycleState.READY, Set.of(
                    ContainerLifecycleState.STOPPING,
                    ContainerLifecycleState.FAILED),
            ContainerLifecycleState.STOPPING, Set.of(
                    ContainerLifecycleState.STOPPED,
                    ContainerLifecycleState.FAILED),
            ContainerLifecycleState.STOPPED, Set.of(),
            ContainerLifecycleState.FAILED, Set.of(
                    ContainerLifecycleState.STOPPING,
                    ContainerLifecycleState.STOPPED)
    );

    private ContainerStateMachine() {
        // utility class
    }

    /**
     * Returns true if the transition from the given state to the target state is valid.
     */
    public static boolean isValidTransition(ContainerLifecycleState from, ContainerLifecycleState to) {
        Set<ContainerLifecycleState> allowed = TRANSITIONS.get(from);
        if (allowed == null) {
            return false;
        }
        return allowed.contains(to);
    }

    /**
     * Returns the set of states reachable from the given state.
     * Returns an empty set for terminal states.
     */
    public static Set<ContainerLifecycleState> validTransitions(ContainerLifecycleState from) {
        Set<ContainerLifecycleState> allowed = TRANSITIONS.get(from);
        if (allowed == null) {
            return Set.of();
        }
        return Set.copyOf(allowed);
    }

    /**
     * Validates a transition. Throws IllegalStateException if the transition is not allowed.
     *
     * @return the target state (for chaining)
     */
    public static ContainerLifecycleState transition(ContainerLifecycleState from, ContainerLifecycleState to) {
        if (!isValidTransition(from, to)) {
            throw new IllegalStateException(
                    "Invalid state transition: " + from + " -> " + to);
        }
        return to;
    }
}
