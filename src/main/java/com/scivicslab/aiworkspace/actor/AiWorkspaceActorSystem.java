package com.scivicslab.aiworkspace.actor;

import com.scivicslab.aiworkspace.backend.jvm.ProcessSupervisor;
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
    private ActorRef<com.scivicslab.aiworkspace.conversation.ConversationLogMergeActor>
            conversationLogMerge;

    @PostConstruct
    void init() {
        actorSystem = new ActorSystem("ai-workspace");
        toolVersions = actorSystem.actorOf("tool-versions", new ToolVersionActor(fetcher));
        buildRegistry = actorSystem.actorOf("build-registry", new BuildRegistryActor());
        conversationLogMerge = actorSystem.actorOf("conversation-log-merge",
                new com.scivicslab.aiworkspace.conversation.ConversationLogMergeActor());
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

    /** The actor holding what the last merge of the conversation logs did. */
    public ActorRef<com.scivicslab.aiworkspace.conversation.ConversationLogMergeActor>
            conversationLogMerge() {
        return conversationLogMerge;
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

    /**
     * Wraps one instance's supervisor in an actor.
     *
     * <p>Named after the tool and the port, which is what identifies an instance everywhere else.
     * The caller keeps the reference; which instances exist is {@code JvmBackend}'s to know.
     *
     * @param supervisor the supervisor to hold
     * @param toolName   the tool
     * @param port       the port that instance listens on
     * @return the instance's actor
     */
    public ActorRef<ProcessSupervisor> newInstance(ProcessSupervisor supervisor,
                                                   String toolName, int port) {
        return actorSystem.actorOf("instance-" + toolName + "-" + port, supervisor);
    }

    /** The system itself, for actors that are created while the portal runs. */
    public ActorSystem actorSystem() {
        return actorSystem;
    }
}
