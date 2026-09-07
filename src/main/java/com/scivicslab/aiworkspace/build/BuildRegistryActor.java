package com.scivicslab.aiworkspace.build;

import com.scivicslab.pojoactor.core.ActorRef;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Which snapshot builds have been started, and where each one's state is.
 *
 * <p>A plain object with no knowledge of threads, held by one actor. The map is an ordinary
 * {@link HashMap}: the request that starts a build and the request that asks about one both reach
 * it through that actor ({@code ActorBasedState_260907_oo01}). k8s-pups's
 * {@code SessionManagerActor} holds its sessions the same way.
 */
public class BuildRegistryActor {

    private final Map<String, ActorRef<BuildJobActor>> jobs = new HashMap<>();

    /** Remembers one build under its own identifier. */
    public void put(String jobId, ActorRef<BuildJobActor> job) {
        jobs.put(jobId, job);
    }

    /** @return the build with that identifier, if it was started by this portal. */
    public Optional<ActorRef<BuildJobActor>> get(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }
}
