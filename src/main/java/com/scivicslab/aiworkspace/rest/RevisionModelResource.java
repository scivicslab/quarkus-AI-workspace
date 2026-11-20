package com.scivicslab.aiworkspace.rest;

import com.scivicslab.aiworkspace.config.RevisionModelSetting;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;

/**
 * The revision model on the Settings screen ({@code RevisionPanel_261120_oo01}). html-saurus asks
 * {@code GET /api/settings/revision-model} each time its Revise panel asks for proposals; a blank answer makes it use
 * the translation model. The candidates are those of the translation model ({@code .../translation-model/candidates}).
 */
@Path("/api/settings/revision-model")
@Produces(MediaType.APPLICATION_JSON)
public class RevisionModelResource {

    @Inject
    RevisionModelSetting setting;

    /** @return {@code {"model": "..."}}, blank when none is chosen */
    @GET
    public String get() {
        return "{\"model\":" + SettingsResource.jsonString(setting.get()) + "}";
    }

    /** Chooses the model; a blank body clears the choice. */
    @POST
    @Consumes(MediaType.TEXT_PLAIN)
    public Response set(String model) {
        try {
            setting.set(model);
        } catch (IOException e) {
            return Response.serverError().entity("{\"error\":" + SettingsResource.jsonString(e.getMessage()) + "}").build();
        }
        return Response.noContent().build();
    }
}
