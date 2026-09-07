package com.scivicslab.aiworkspace.rest;

import com.scivicslab.aiworkspace.conversation.ConversationLogMerge;
import com.scivicslab.aiworkspace.conversation.ConversationLogMergeActor;
import com.scivicslab.aiworkspace.conversation.ConversationLogSearch;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * The Conversations screen: one search box over the conversations of every tool instance
 * ({@code ConversationLogSearchPlacement_260908_oo01}).
 *
 * <p>Each tool instance keeps its conversations in its own database file, so a conversation held
 * two weeks ago is only reachable by remembering which port it was held on. This screen reads the
 * merged database instead, where every instance's conversations sit together.</p>
 */
@Path("/conversations")
public class ConversationResource {

    @Inject
    Template conversations;

    @Inject
    ConversationLogSearch search;

    @Inject
    ConversationLogMerge merge;

    @Inject
    com.scivicslab.aiworkspace.actor.AiWorkspaceActorSystem actors;

    @Inject
    com.scivicslab.aiworkspace.web.AssetVersion assetVersion;

    /** How many matching turns one search returns. */
    private static final int LIMIT = 100;

    @ConfigProperty(name = "quarkus.application.version", defaultValue = "dev")
    String appVersion;

    @ConfigProperty(name = "ai-workspace.image-tag")
    Optional<String> imageTag;

    /**
     * Draws the screen, with results when a query was given.
     *
     * @param q what to look for; blank draws the screen with no results
     * @return the rendered screen
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance screen(@QueryParam("q") @DefaultValue("") String q) {
        ConversationLogSearch.Overview overview = search.overview();
        ConversationLogSearch.Result result = search.search(q, LIMIT);
        var mergeState = actors.conversationLogMerge();
        boolean merging = Boolean.TRUE.equals(
                mergeState.ask(ConversationLogMergeActor::running).join());
        ConversationLogMergeActor.Outcome lastMerge =
                mergeState.ask(ConversationLogMergeActor::last).join();
        return conversations
                .data("screen", "conversations")
                .data("version", appVersion)
                .data("assetVersion", assetVersion.value())
                .data("imageTag", imageTag.orElse(""))
                .data("q", q)
                .data("database", search.databasePath())
                .data("overview", overview)
                .data("hits", result.hits())
                .data("limited", result.limited())
                .data("limit", LIMIT)
                .data("searched", !q.isBlank())
                .data("merging", merging)
                .data("lastMerge", lastMerge)
                .data("scanDir", merge.scanDirectory())
                .data("prefixes", merge.prefixes())
                .data("error", result.error().isBlank() ? overview.error() : result.error());
    }

    /**
     * Brings every tool instance's conversations into the merged database, then comes back to the
     * screen.
     *
     * <p>Answers {@code 303} rather than drawing the screen itself, so that reloading the page
     * afterwards does not start another merge.</p>
     *
     * @param q the search the visitor had typed, sent as a hidden field and kept across the
     *          redirect
     * @return a redirect back to the screen
     */
    @POST
    @Path("/merge")
    public Response startMerge(@jakarta.ws.rs.FormParam("q") @DefaultValue("") String q) {
        merge.start();
        String where = q.isBlank() ? "/conversations"
                : "/conversations?q=" + java.net.URLEncoder.encode(q,
                        java.nio.charset.StandardCharsets.UTF_8);
        return Response.seeOther(java.net.URI.create(where)).build();
    }

    /**
     * Returns one turn's whole text, for the screen to show under a result.
     *
     * @param logId the row in the {@code logs} table
     * @return the message, or {@code ""} when there is no such row
     */
    @GET
    @Path("/turn/{logId}")
    @Produces(MediaType.TEXT_PLAIN)
    public String turn(@PathParam("logId") long logId) {
        return search.message(logId);
    }
}
