package com.scivicslab.aiworkspace.node;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.scivicslab.aiworkspace.actor.AiWorkspaceActorSystem;
import com.scivicslab.aiworkspace.config.PortalPort;
import com.scivicslab.aiworkspace.config.ToolRegistryEntry;
import com.scivicslab.aiworkspace.config.ToolRegistryLoader;
import com.scivicslab.aiworkspace.rest.ServiceDirectoryResource;
import com.scivicslab.aiworkspace.spi.ServiceBackend;
import com.scivicslab.aiworkspace.version.InstalledVersionReader;
import com.scivicslab.pojoactor.distributed.AllowListDispatcher.AllowedAction;
import com.scivicslab.pojoactor.distributed.DistributedIIActorSystem;
import com.scivicslab.pojoactor.distributed.NodeInfo;
import com.scivicslab.pojoactor.distributed.discovery.StaticNodeDiscovery;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Makes this AI-workspace a node of the distributed actor system (ActorNodesForWorkspaceAndPortal_261118_oo01).
 *
 * <p>AI-workspace's own actor system is the node; there is no second one (decision 1). At startup this puts the
 * gateway {@value #GATEWAY} at the root of that tree and opens the receiver on 127.0.0.1 at the actor port. Other
 * nodes may call only the actions of {@link #ALLOWED}; every other actor of the tree ({@code instances},
 * {@code instance-<tool>-<port>}, {@code build-registry}, ...) is refused, even by name.</p>
 */
@ApplicationScoped
public class WorkspaceNode {

    private static final Logger LOG = Logger.getLogger(WorkspaceNode.class.getName());

    /** The one actor other nodes may call. */
    public static final String GATEWAY = "ai-workspace-remote";
    /** This node's kind, in the node list. */
    public static final String KIND = "ai-workspace";
    /** The actions other nodes may call on {@link #GATEWAY}: reads only (decision 2). */
    public static final List<String> ACTIONS = List.of("listInstances", "listCatalog", "listNodes");
    /** The allow-list: {@link #GATEWAY} with each of {@link #ACTIONS}, and nothing else. */
    public static final Set<AllowedAction> ALLOWED = ACTIONS.stream()
            .map(a -> new AllowedAction(GATEWAY, a)).collect(Collectors.toUnmodifiableSet());
    /** The receiver listens here only: other machines cannot reach it. */
    public static final String BIND = "127.0.0.1";

    @Inject
    AiWorkspaceActorSystem actors;

    @Inject
    ServiceBackend backend;

    @Inject
    InstalledVersionReader installedVersionReader;

    /**
     * Builds the actor system of a node named {@code nodeName} that takes messages on {@code actorPort}, with the
     * allow-list. Nothing listens until {@link #open}.
     */
    public static DistributedIIActorSystem buildSystem(String nodeName, int actorPort) {
        NodeInfo me = new NodeInfo(nodeName, BIND, actorPort);
        return DistributedIIActorSystem.builder()
                .discovery(new StaticNodeDiscovery(me, List.of()))
                .allowedActions(ALLOWED)
                // Every allowed action is a read: a message delivered twice answers the same.
                .noDeduplication()
                .build();
    }

    /** Puts {@code gateway} at the root of {@code system} as {@value #GATEWAY} and opens the receiver on {@code bind}. */
    public static void open(DistributedIIActorSystem system, WorkspaceGateway gateway, String bind) throws IOException {
        system.addIIActor(new WorkspaceGatewayIIAR(GATEWAY, gateway, system));
        system.startHttpServer(bind, system.getMyNode().getPort());
    }

    void onStart(@Observes StartupEvent event) {
        DistributedIIActorSystem system = actors.actorSystem();
        NodeInfo me = system.getMyNode();
        WorkspaceGateway gateway = new WorkspaceGateway(me.getNodeId(), me.getPort(),
                q -> ServiceDirectoryResource.list(backend, q.tool(), q.readyOnly()), this::catalog);
        try {
            open(system, gateway, BIND);
            LOG.info("Node " + me.getNodeId() + " takes messages on " + BIND + ":" + me.getPort()
                    + "; other nodes may call " + GATEWAY + " " + ACTIONS);
        } catch (IOException e) {
            // The portal runs on without the node: the dashboard and the HTTP API do not depend on it.
            LOG.warning("Node " + me.getNodeId() + " could not listen on " + BIND + ":" + me.getPort()
                    + " (" + e.getMessage() + "); other nodes cannot reach this AI-workspace");
        }
    }

    /** The catalog as the dashboard shows it: each tool with its installed and latest versions. */
    List<WorkspaceGateway.CatalogEntry> catalog() {
        Map<String, ToolRegistryEntry> registry = new LinkedHashMap<>();
        for (ToolRegistryEntry e : ToolRegistryLoader.load()) registry.put(e.name(), e);
        List<WorkspaceGateway.CatalogEntry> out = new ArrayList<>();
        for (var tool : backend.getDashboardModel().launchTools()) {
            ToolRegistryEntry entry = registry.get(tool.name());
            boolean library = entry != null && entry.library();
            String installed = entry == null || library ? "" : installedVersionReader.read(entry.jarFileName());
            var remote = actors.toolVersions().ask(a -> a.get(tool.name())).join();
            out.add(new WorkspaceGateway.CatalogEntry(tool.name(), tool.displayName(), tool.status(), installed,
                    remote.latestRelease(), remote.latestSnapshot()));
        }
        return out;
    }

    /** The node name and actor port this portal takes, from its HTTP port. */
    public static NodeInfo identity() {
        int httpPort = PortalPort.number();
        return new NodeInfo(NodeIdentity.name(httpPort), BIND, NodeIdentity.actorPort(httpPort));
    }
}
