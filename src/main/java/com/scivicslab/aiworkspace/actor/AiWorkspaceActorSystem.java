package com.scivicslab.aiworkspace.actor;

import com.scivicslab.aiworkspace.build.BuildJobActor;
import com.scivicslab.aiworkspace.build.BuildRegistryActor;
import com.scivicslab.aiworkspace.version.GitHubVersionFetcher;
import com.scivicslab.aiworkspace.version.ToolVersionActor;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.logging.Logger;

/**
 * Owns the actor system and the actors that hold this portal's mutable state.
 *
 * <p>Everything two threads would otherwise share lives in an actor: a caller sends a lambda and
 * either waits for the answer or does not, and the object inside is touched by one thread at a
 * time. Nothing here writes {@code volatile}, {@code synchronized} or {@code ConcurrentHashMap}
 * ({@code ActorBasedState_260907_oo01}).
 *
 * <p>k8s-pups's {@code K8sPupsActorSystem} is the same class for the same job.
 */
@ApplicationScoped
@Startup
public class AiWorkspaceActorSystem {

    private static final Logger logger = Logger.getLogger(AiWorkspaceActorSystem.class.getName());

    @Inject
    GitHubVersionFetcher fetcher;

    private ActorSystem actorSystem;
    private ActorRef<ToolVersionActor> toolVersions;
    private ActorRef<BuildRegistryActor> buildRegistry;

    @PostConstruct
    void init() {
        actorSystem = new ActorSystem("ai-workspace");
        toolVersions = actorSystem.actorOf("tool-versions", new ToolVersionActor(fetcher));
        buildRegistry = actorSystem.actorOf("build-registry", new BuildRegistryActor());
        logger.info("AiWorkspaceActorSystem initialized");
    }

    @PreDestroy
    void shutdown() {
        if (actorSystem != null) {
            actorSystem.terminate();
            logger.info("AiWorkspaceActorSystem terminated");
        }
    }

    /** The actor holding what GitHub said about each tool's repository. */
    public ActorRef<ToolVersionActor> toolVersions() {
        return toolVersions;
    }

    /** The actor holding which snapshot builds have been started. */
    public ActorRef<BuildRegistryActor> buildRegistry() {
        return buildRegistry;
    }

    /**
     * Creates one build's actor as a child of the registry, and remembers it there.
     *
     * @param jobId the identifier the caller will ask about it by
     * @param tool  the tool being built
     * @return the new build's actor
     */
    public ActorRef<BuildJobActor> newBuildJob(String jobId, String tool) {
        ActorRef<BuildJobActor> job =
            buildRegistry.createChild("build-" + jobId, new BuildJobActor(jobId, tool));
        buildRegistry.tell(r -> r.put(jobId, job));
        return job;
    }

    /** The system itself, for actors that are created while the portal runs. */
    public ActorSystem actorSystem() {
        return actorSystem;
    }
}
