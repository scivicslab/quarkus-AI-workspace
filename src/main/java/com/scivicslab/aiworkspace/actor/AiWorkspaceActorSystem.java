package com.scivicslab.aiworkspace.actor;

import com.scivicslab.aiworkspace.build.BuildJobActor;
import com.scivicslab.aiworkspace.build.Builds;
import com.scivicslab.aiworkspace.version.GitHubVersionFetcher;
import com.scivicslab.aiworkspace.version.ToolVersionActor;
import com.scivicslab.pojoactor.core.ActorRef;

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

    /** This portal's actor system, which is also its node of the distributed actor system (ActorNodesForWorkspaceAndPortal_261118_oo01). */
    private com.scivicslab.pojoactor.distributed.DistributedIIActorSystem actorSystem;
    private ActorRef<ToolVersionActor> toolVersions;
    private ActorRef<Builds> buildRegistry;
    private ActorRef<com.scivicslab.aiworkspace.dashboard.Dashboard> dashboard;
    private ActorRef<com.scivicslab.aiworkspace.conversation.ConversationLogMergeActor>
            conversationLogMerge;

    @PostConstruct
    void init() {
        var me = com.scivicslab.aiworkspace.node.WorkspaceNode.identity();
        actorSystem = com.scivicslab.aiworkspace.node.WorkspaceNode.buildSystem(me.getNodeId(), me.getPort());
        toolVersions = actorSystem.actorOf("tool-versions", new ToolVersionActor(fetcher));
        buildRegistry = actorSystem.actorOf("build-registry", new Builds());
        // The user's choice of tiles; it reads its file now and writes it on every save (DashboardBuilder_261118_oo01).
        dashboard = actorSystem.actorOf("dashboard", com.scivicslab.aiworkspace.dashboard.Dashboard.open(
                com.scivicslab.aiworkspace.dashboard.DashboardFile.location()));
        conversationLogMerge = actorSystem.actorOf("conversation-log-merge",
                new com.scivicslab.aiworkspace.conversation.ConversationLogMergeActor());
        logger.info("AiWorkspaceActorSystem initialized");
    }

    @PreDestroy
    void shutdown() {
        if (actorSystem != null) {
            // Also stops the receiver, when the node opened one.
            actorSystem.close();
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

    /** The actor holding which catalog entries the user shows on the Dashboard tab, in what order and under what heading. */
    public ActorRef<com.scivicslab.aiworkspace.dashboard.Dashboard> dashboard() {
        return dashboard;
    }

    /** The actor holding which snapshot builds have been started. */
    public ActorRef<Builds> buildRegistry() {
        return buildRegistry;
    }

    /**
     * Creates one build's actor as a child of the registry. Being its child is all the remembering there is.
     *
     * @param jobId the identifier the caller will ask about it by
     * @param tool  the tool being built
     * @return the new build's actor
     */
    public ActorRef<BuildJobActor> newBuildJob(String jobId, String tool) {
        return buildRegistry.createChild(BUILD_PREFIX + jobId, new BuildJobActor(jobId, tool));
    }

    /**
     * The build with that identifier, if this portal started it: one of the registry's children. Finding never
     * creates, and an actor named like a build that is not the registry's child is not a build.
     */
    public java.util.Optional<ActorRef<BuildJobActor>> buildJob(String jobId) {
        String name = BUILD_PREFIX + jobId;
        if (!buildRegistry.getNamesOfChildren().contains(name)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(actorSystem.<BuildJobActor>getActor(name));
    }

    private static final String BUILD_PREFIX = "build-";

    /** The system itself, for actors that are created while the portal runs. */
    public com.scivicslab.pojoactor.distributed.DistributedIIActorSystem actorSystem() {
        return actorSystem;
    }
}
