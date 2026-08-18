package com.scivicslab.aiworkspace.rest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Reads and writes the single environment-wide runtime config file at
 * {@code ${user.dir}/config/application.properties} — the file Quarkus's
 * own {@code $PWD/config/application.properties} convention lets every tool
 * launched from this working directory pick up at its own startup. Holds
 * environment facts (real node IPs, per-instance capacity overrides) that
 * are properties of this deployment, not of any one tool, kept out of
 * source repos, and shared rather than duplicated per tool — see
 * quarkus-gpu-broker's {@code CapabilityConfig_260810_oo01} for the
 * motivating example.
 *
 * <p>Deliberately scoped to this ONE fixed path, not a general file editor,
 * so the dashboard never exposes arbitrary filesystem read/write.
 */
@Path("/env-config")
public class EnvConfigResource {

    @Inject
    @Location("env-config.html")
    Template envConfig;

    private static java.nio.file.Path configFile() {
        return java.nio.file.Path.of(System.getProperty("user.dir"), "config", "application.properties");
    }

    /** The edit page. */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance page() {
        return envConfig.instance();
    }

    /** Current file content, or empty string if the file does not exist yet. */
    @GET
    @Path("/content")
    @Produces(MediaType.TEXT_PLAIN)
    public String content() {
        java.nio.file.Path file = configFile();
        if (!Files.exists(file)) {
            return "";
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new WebApplicationException("failed to read " + file, e, Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    /** Overwrites the file with the given content, creating config/ if needed. */
    @POST
    @Path("/content")
    @Consumes(MediaType.TEXT_PLAIN)
    public Response save(String body) {
        java.nio.file.Path file = configFile();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, body == null ? "" : body, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new WebApplicationException("failed to write " + file, e, Response.Status.INTERNAL_SERVER_ERROR);
        }
        return Response.noContent().build();
    }
}
