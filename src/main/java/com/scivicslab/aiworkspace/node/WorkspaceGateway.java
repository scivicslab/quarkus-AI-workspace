package com.scivicslab.aiworkspace.node;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.aiworkspace.rest.ServiceDirectoryResource;
import com.scivicslab.pojoactor.action.ActionResult;

/**
 * What other nodes may ask this AI-workspace: the running tools, the catalog and the node list
 * (ActorNodesForWorkspaceAndPortal_261118_oo01, {@code ai-workspace-remote}). Read actions only; it starts and stops
 * nothing (decision 2). Holds no state.
 *
 * <p>Each action takes one JSON object and answers a JSON array. Each call is written to the application log, one line
 * (decision 8).</p>
 */
public final class WorkspaceGateway {

    private static final Logger LOG = Logger.getLogger(WorkspaceGateway.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Which instances {@code listInstances} asks for. */
    public record InstanceQuery(String tool, boolean readyOnly) {}

    /** One tool of the catalog, with the versions the dashboard shows. */
    public record CatalogEntry(String name, String displayName, String status, String installed,
                               String latestRelease, String latestSnapshot) {}

    /** One node, as {@code listNodes} and {@code GET /api/nodes} list it. */
    public record NodeEntry(String name, String host, int actorPort, String kind) {}

    private final String nodeName;
    private final int actorPort;
    private final Function<InstanceQuery, List<ServiceDirectoryResource.ServiceEntry>> instances;
    private final Supplier<List<CatalogEntry>> catalog;

    /**
     * @param nodeName  this node's name
     * @param actorPort this node's actor port
     * @param instances answers {@code listInstances}: the same as {@code GET /api/services}
     * @param catalog   answers {@code listCatalog}
     */
    public WorkspaceGateway(String nodeName, int actorPort,
                            Function<InstanceQuery, List<ServiceDirectoryResource.ServiceEntry>> instances,
                            Supplier<List<CatalogEntry>> catalog) {
        this.nodeName = nodeName;
        this.actorPort = actorPort;
        this.instances = instances;
        this.catalog = catalog;
    }

    /** {@code {"tool": name (optional), "readyOnly": boolean}} -> the running tools. */
    public ActionResult listInstances(String args) {
        return answer("listInstances", () -> {
            JsonNode a = parse(args);
            String tool = a.hasNonNull("tool") ? a.get("tool").asText() : null;
            return instances.apply(new InstanceQuery(tool, a.path("readyOnly").asBoolean(false)));
        });
    }

    /** -> the tools that can be started, with their versions. */
    public ActionResult listCatalog(String args) {
        return answer("listCatalog", catalog::get);
    }

    /** -> the nodes AI-workspace knows: for now, itself. */
    public ActionResult listNodes(String args) {
        return answer("listNodes", () -> List.of(new NodeEntry(nodeName, "127.0.0.1", actorPort, WorkspaceNode.KIND)));
    }

    private static JsonNode parse(String args) throws Exception {
        return args == null || args.isBlank() ? JSON.createObjectNode() : JSON.readTree(args);
    }

    /** Runs one action, writes its log line, and answers its value as JSON or the failure as text. */
    private ActionResult answer(String action, Answer work) {
        long start = System.nanoTime();
        try {
            String json = JSON.writeValueAsString(work.get());
            LOG.info("remote call " + action + " ok in " + (System.nanoTime() - start) / 1_000_000 + " ms");
            return new ActionResult(true, json);
        } catch (Exception e) {
            LOG.warning("remote call " + action + " failed in " + (System.nanoTime() - start) / 1_000_000 + " ms: " + e);
            return new ActionResult(false, action + " failed: " + e.getMessage());
        }
    }

    @FunctionalInterface
    private interface Answer {
        Object get() throws Exception;
    }
}
