package com.scivicslab.aiworkspace.rest;

import com.scivicslab.aiworkspace.model.DashboardModel;
import com.scivicslab.aiworkspace.model.SessionState;
import com.scivicslab.aiworkspace.model.SessionView;
import com.scivicslab.aiworkspace.spi.ServiceBackend;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.ArrayList;
import java.util.List;

/**
 * Answers "what is running, and where" for the tools this portal launched
 * ({@code ServiceDirectory_260905_oo01}).
 *
 * <p>A tool cannot be handed this at launch: the set changes while it runs — another
 * {@code quarkus-chat-ui} starts, one stops — so what it is handed is the address to ask at
 * ({@code AI_WORKSPACE_URL}), and it asks when it needs to know.</p>
 *
 * <p>Separate from {@code GET /api/status}, which serves the dashboard and carries every session's
 * whole progress log: measured at 572 KB on a portal with eleven tools running. A caller resolving
 * a name should not download eleven tools' log history to find a port number.</p>
 */
@Path("/api/services")
@Produces(MediaType.APPLICATION_JSON)
public class ServiceDirectoryResource {

    @Inject
    ServiceBackend backend;

    /**
     * One running tool, with what a caller needs to reach it and nothing else.
     *
     * @param name  the tool's name, as the tool registry names it, e.g. {@code "html-saurus"}
     * @param port  the port it listens on
     * @param url   the base URL to address it at
     * @param state {@code READY}, {@code STARTING} or {@code FAILED}
     * @param title the title its launcher gave this instance, or {@code ""} — what distinguishes
     *              two instances of the same tool from each other
     */
    public record ServiceEntry(String name, int port, String url, String state, String title) {}

    /**
     * Lists the running tools.
     *
     * @param name when given, only tools of that name; a conversation looking for a peer
     *             {@code quarkus-chat-ui} asks for one name rather than filtering the whole list
     * @param readyOnly when {@code true}, only tools that can be talked to now
     * @return the matching tools
     */
    @GET
    public List<ServiceEntry> services(@QueryParam("name") String name,
                                       @QueryParam("readyOnly") boolean readyOnly) {
        DashboardModel model = backend.getDashboardModel();
        List<SessionView> all = new ArrayList<>();
        all.addAll(model.managementServices());
        all.addAll(model.activeSessions());

        List<ServiceEntry> out = new ArrayList<>();
        for (SessionView s : all) {
            if (name != null && !name.isBlank() && !name.equals(s.toolName())) continue;
            if (readyOnly && s.state() != SessionState.READY) continue;
            out.add(new ServiceEntry(
                    s.toolName(),
                    s.port(),
                    s.accessUrl() != null ? s.accessUrl() : "http://localhost:" + s.port() + "/",
                    String.valueOf(s.state()),
                    s.params() == null ? "" : s.params().getOrDefault("title", "")));
        }
        return out;
    }
}
