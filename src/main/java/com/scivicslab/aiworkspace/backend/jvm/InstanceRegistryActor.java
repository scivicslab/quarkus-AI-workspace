package com.scivicslab.aiworkspace.backend.jvm;

import com.scivicslab.aiworkspace.model.SessionState;
import com.scivicslab.pojoactor.core.ActorRef;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which instances this portal has, and which tool each belongs to.
 *
 * <p>A plain object with no knowledge of threads, held by one actor. The map is an ordinary
 * {@link HashMap} of ordinary lists: everything that reads or changes it goes through that actor,
 * so nothing needs guarding ({@code ActorBasedState_260907_oo01}). k8s-pups's
 * {@code SessionManagerActor} holds its sessions the same way.
 *
 * <p>A {@code ConcurrentHashMap} would not have been enough anyway. It makes each single operation
 * safe on its own, and this class does things that span two: "take the list, drop what has stopped,
 * then choose a port that none of the rest is on" has to see one list throughout.
 */
public class InstanceRegistryActor {

    private final Map<String, List<ActorRef<ProcessSupervisor>>> byTool = new HashMap<>();

    /** Adds one instance under its tool. */
    public void add(String toolName, ActorRef<ProcessSupervisor> instance) {
        byTool.computeIfAbsent(toolName, k -> new ArrayList<>()).add(instance);
    }

    /** @return that tool's instances, as they stand; never null. */
    public List<ActorRef<ProcessSupervisor>> forTool(String toolName) {
        return List.copyOf(byTool.getOrDefault(toolName, List.of()));
    }

    /** @return every instance of every tool. */
    public List<ActorRef<ProcessSupervisor>> all() {
        List<ActorRef<ProcessSupervisor>> everything = new ArrayList<>();
        byTool.values().forEach(everything::addAll);
        return everything;
    }

    /**
     * Makes sure the tool is known, and answers with the instances it already has.
     *
     * <p>One message, not two: the startup scans read the list to decide whether to adopt or start
     * anything, and adding the tool must not lose what an earlier scan already put there.
     */
    public List<ActorRef<ProcessSupervisor>> ensureAndList(String toolName) {
        return List.copyOf(byTool.computeIfAbsent(toolName, k -> new ArrayList<>()));
    }

    /**
     * Drops the instances of one tool that have stopped or failed, and returns what is left.
     *
     * <p>One message, not two: choosing a port for a new instance depends on which instances are
     * still there, and the answer must be the same list that the dropping produced.
     */
    public List<ActorRef<ProcessSupervisor>> dropFinishedAndList(String toolName) {
        List<ActorRef<ProcessSupervisor>> list =
            byTool.computeIfAbsent(toolName, k -> new ArrayList<>());
        list.removeIf(s -> {
            SessionState state = s.ask(ProcessSupervisor::getState).join();
            return state == SessionState.STOPPED || state == SessionState.FAILED;
        });
        return List.copyOf(list);
    }

    /** Removes every instance of one tool, whatever state they are in. */
    public void clear(String toolName) {
        byTool.getOrDefault(toolName, new ArrayList<>()).clear();
    }
}
