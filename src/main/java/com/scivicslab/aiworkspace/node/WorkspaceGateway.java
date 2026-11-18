package com.scivicslab.aiworkspace.node;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.function.Supplier;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scivicslab.aiworkspace.model.NodeAnnouncement;
import com.scivicslab.aiworkspace.rest.ServiceDirectoryResource;
import com.scivicslab.pojoactor.action.ActionResult;

/**
 * What other nodes may ask this AI-workspace: the running tools, the catalog and the node list, and the one write, a
 * tool announcing itself (ActorNodesForWorkspaceAndPortal_261118_oo01, {@code ai-workspace-remote}). It starts and
 * stops nothing (decision 2). Holds no state: an announcement is kept by the instance it is about.
 *
 * <p>Each action takes one JSON object and answers JSON. Each call is written to the application log, one line
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

    private final Supplier<List<NodeEntry>> nodes;
    private final Function<InstanceQuery, List<ServiceDirectoryResource.ServiceEntry>> instances;
    private final Supplier<List<CatalogEntry>> catalog;
    private final Function<NodeAnnouncement, Optional<String>> announce;

    /** A node or kind name: letters, digits, '.', '_' and '-'. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    /**
     * @param nodes     answers {@code listNodes}: the same as {@code GET /api/nodes}
     * @param instances answers {@code listInstances}: the same as {@code GET /api/services}
     * @param catalog   answers {@code listCatalog}
     * @param announce  takes a well-formed announcement: empty when it was taken, or why it was refused
     */
    public WorkspaceGateway(Supplier<List<NodeEntry>> nodes,
                            Function<InstanceQuery, List<ServiceDirectoryResource.ServiceEntry>> instances,
                            Supplier<List<CatalogEntry>> catalog,
                            Function<NodeAnnouncement, Optional<String>> announce) {
        this.nodes = nodes;
        this.instances = instances;
        this.catalog = catalog;
        this.announce = announce;
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

    /** -> the nodes AI-workspace knows, as {@code GET /api/nodes} lists them. */
    public ActionResult listNodes(String args) {
        return answer("listNodes", nodes::get);
    }

    /**
     * {@code {"name", "kind", "actorPort", "httpPort"}} -> {@code {"accepted": true}}, or
     * {@code {"accepted": false, "reason": ...}} when no READY instance of this AI-workspace holds that HTTP port.
     * Fails on an announcement that is not well formed. The same announcement twice leaves the same node list.
     */
    public ActionResult announceNode(String args) {
        return answer("announceNode", () -> {
            JsonNode a = parse(args);
            NodeAnnouncement n = new NodeAnnouncement(name(a, "name"), name(a, "kind"), port(a, "actorPort"), port(a, "httpPort"));
            Optional<String> refused = announce.apply(n);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("accepted", refused.isEmpty());
            refused.ifPresent(r -> out.put("reason", r));
            LOG.info("announceNode " + n.name() + " (" + n.kind() + ", actor port " + n.actorPort() + ", HTTP port "
                    + n.httpPort() + "): " + refused.map(r -> "refused, " + r).orElse("accepted"));
            return out;
        });
    }

    private static String name(JsonNode a, String field) {
        String v = a.path(field).asText("");
        if (!NAME.matcher(v).matches()) throw new IllegalArgumentException(field + " is not a name: '" + v + "'");
        return v;
    }

    private static int port(JsonNode a, String field) {
        JsonNode v = a.path(field);
        if (!v.canConvertToInt() || v.asInt() < 1 || v.asInt() > 65535) {
            throw new IllegalArgumentException(field + " is not a port number: " + v);
        }
        return v.asInt();
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
