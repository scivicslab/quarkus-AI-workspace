package com.scivicslab.aiworkspace.dashboard;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;

import com.scivicslab.aiworkspace.actor.AiWorkspaceActorSystem;
import com.scivicslab.aiworkspace.config.ToolRegistryEntry;
import com.scivicslab.aiworkspace.config.ToolRegistryLoader;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * What the Catalog tab reads and saves: which catalog entries the Dashboard tab shows, in what order and under what
 * heading (DashboardBuilder_261118_oo01).
 *
 * <p>Under {@code /api/dashboard/} rather than {@code /api/catalog/}: what these two read and write is the user's
 * dashboard file, not the catalog, which no user changes.</p>
 */
@Path("/api/dashboard/layout")
@Produces(MediaType.APPLICATION_JSON)
public class DashboardLayoutResource {

    /** What {@code PUT} takes: the tiles to show, in order. */
    public record Tiles(List<TileSpec> tiles) {}

    @Inject
    AiWorkspaceActorSystem actors;

    /** @return the shown tiles in the user's order, the hidden entries and the tiles the catalog no longer has */
    @GET
    public Layout layout() {
        List<ToolRegistryEntry> catalog = ToolRegistryLoader.load();
        return actors.dashboard().ask(d -> d.layout(catalog)).join();
    }

    /**
     * Saves the tiles to show, in order, and answers the layout after the save. Refuses (400) a name the catalog does
     * not have, unless the dashboard file already listed it.
     */
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    public Response save(Tiles body) {
        if (body == null || body.tiles() == null) {
            return Response.status(400).entity(Map.of("error", "expected {\"tiles\": [{\"name\", \"title\"}...]}")).build();
        }
        List<ToolRegistryEntry> catalog = ToolRegistryLoader.load();
        try {
            Layout after = actors.dashboard().ask(d -> {
                try {
                    d.save(body.tiles(), catalog);
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
                return d.layout(catalog);
            }).join();
            return Response.ok(after).build();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IllegalArgumentException) {
                return Response.status(400).entity(Map.of("error", cause.getMessage())).build();
            }
            return Response.status(500).entity(Map.of("error", "the dashboard file could not be written: "
                    + (cause == null ? e.getMessage() : cause.getMessage()))).build();
        }
    }
}
