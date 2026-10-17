package com.scivicslab.aiworkspace.rest;

import com.scivicslab.aiworkspace.config.TranslationModelSetting;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The values chosen on the Settings screen, for the screen to save and for a tool to ask.
 *
 * <p>html-saurus asks {@code GET /api/settings/translation-model} (at {@code AI_WORKSPACE_URL})
 * each time it translates, so a model chosen here takes effect without restarting it.</p>
 */
@Path("/api/settings/translation-model")
@Produces(MediaType.APPLICATION_JSON)
public class SettingsResource {

    private static final Pattern MODEL_ID = Pattern.compile("\"id\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    @Inject
    TranslationModelSetting setting;

    /** @return {@code {"model": "<id>"}}; the id is {@code ""} when none is chosen */
    @GET
    public String get() {
        return "{\"model\":" + jsonString(setting.get()) + "}";
    }

    /** Saves the model id sent as the plain-text body; an empty body clears the choice. */
    @POST
    @Consumes(MediaType.TEXT_PLAIN)
    public Response set(String model) {
        try {
            setting.set(model);
        } catch (IOException e) {
            return Response.serverError().entity("{\"error\":" + jsonString(e.getMessage()) + "}").build();
        }
        return Response.noContent().build();
    }

    /**
     * The models the GPU broker serves, to choose from.
     *
     * @return {@code {"models": ["<id>", ...]}}; the list is empty when no broker is configured or
     *         it does not answer, and the screen then shows only the current value
     */
    @GET
    @Path("/candidates")
    public String candidates() {
        String broker = System.getProperty("gpu.broker.url");
        if (broker == null || broker.isBlank()) broker = System.getenv("GPU_BROKER_URL");
        List<String> ids = new ArrayList<>();
        if (broker != null && !broker.isBlank()) {
            try {
                HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(3)).build();
                HttpResponse<String> r = client.send(
                        HttpRequest.newBuilder(URI.create(broker.replaceAll("/+$", "") + "/v1/models"))
                                .timeout(Duration.ofSeconds(5)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) ids = modelIds(r.body());
            } catch (IOException | IllegalArgumentException e) {
                // leave the list empty; the screen falls back to the current value
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        StringBuilder sb = new StringBuilder("{\"models\":[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(jsonString(ids.get(i)));
        }
        return sb.append("]}").toString();
    }

    /** The {@code id} of each entry in an OpenAI-style {@code /v1/models} response. */
    static List<String> modelIds(String modelsJson) {
        List<String> ids = new ArrayList<>();
        Matcher m = MODEL_ID.matcher(modelsJson);
        while (m.find()) {
            ids.add(m.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
        }
        return ids;
    }

    private static String jsonString(String s) {
        String v = s == null ? "" : s;
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
