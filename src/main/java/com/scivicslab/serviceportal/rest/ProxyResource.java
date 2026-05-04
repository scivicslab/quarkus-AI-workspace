package com.scivicslab.serviceportal.rest;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * Reverse proxy: forwards /proxy/{port}/{rest} → http://localhost:{port}/{rest}
 *
 * JAX-RS path /proxy/{port}/{rest:.*} is more specific than DashboardResource's @Path("/"),
 * so RESTEasy matches this resource first.
 */
@Path("/proxy/{port}")
public class ProxyResource {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "transfer-encoding", "te",
            "trailer", "upgrade", "proxy-authorization", "proxy-authenticate",
            "host");

    @GET    @Path("{rest:.*}") public CompletionStage<Response> doGet(   @PathParam("port") int p, @PathParam("rest") String r, @Context UriInfo u, @Context HttpHeaders h)                { return proxy(p, r, u, h, "GET",     null); }
    @POST   @Path("{rest:.*}") public CompletionStage<Response> doPost(  @PathParam("port") int p, @PathParam("rest") String r, @Context UriInfo u, @Context HttpHeaders h, InputStream b) { return proxy(p, r, u, h, "POST",    b);    }
    @PUT    @Path("{rest:.*}") public CompletionStage<Response> doPut(   @PathParam("port") int p, @PathParam("rest") String r, @Context UriInfo u, @Context HttpHeaders h, InputStream b) { return proxy(p, r, u, h, "PUT",     b);    }
    @DELETE @Path("{rest:.*}") public CompletionStage<Response> doDelete(@PathParam("port") int p, @PathParam("rest") String r, @Context UriInfo u, @Context HttpHeaders h)                { return proxy(p, r, u, h, "DELETE",  null); }
    @HEAD   @Path("{rest:.*}") public CompletionStage<Response> doHead(  @PathParam("port") int p, @PathParam("rest") String r, @Context UriInfo u, @Context HttpHeaders h)                { return proxy(p, r, u, h, "HEAD",    null); }
    @OPTIONS @Path("{rest:.*}") public CompletionStage<Response> doOpts( @PathParam("port") int p, @PathParam("rest") String r, @Context UriInfo u, @Context HttpHeaders h)                { return proxy(p, r, u, h, "OPTIONS", null); }
    @PATCH  @Path("{rest:.*}") public CompletionStage<Response> doPatch( @PathParam("port") int p, @PathParam("rest") String r, @Context UriInfo u, @Context HttpHeaders h, InputStream b) { return proxy(p, r, u, h, "PATCH",   b);    }

    private CompletionStage<Response> proxy(int port, String rest, UriInfo uriInfo,
                                            HttpHeaders reqHeaders, String method, InputStream body) {
        String query = uriInfo.getRequestUri().getRawQuery();
        String targetUrl = "http://localhost:" + port + "/" + (rest == null ? "" : rest)
                + (query != null && !query.isEmpty() ? "?" + query : "");

        byte[] bodyBytes;
        try {
            bodyBytes = (body != null) ? body.readAllBytes() : new byte[0];
        } catch (Exception e) {
            bodyBytes = new byte[0];
        }

        HttpRequest.BodyPublisher publisher = bodyBytes.length > 0
                ? HttpRequest.BodyPublishers.ofByteArray(bodyBytes)
                : HttpRequest.BodyPublishers.noBody();

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(targetUrl))
                .method(method, publisher);

        reqHeaders.getRequestHeaders().forEach((k, vals) -> {
            if (!HOP_BY_HOP.contains(k.toLowerCase())) {
                vals.forEach(v -> {
                    try { builder.header(k, v); } catch (IllegalArgumentException ignored) {}
                });
            }
        });

        return CLIENT.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(resp -> {
                    Response.ResponseBuilder rb = Response.status(resp.statusCode())
                            .entity(resp.body());
                    resp.headers().map().forEach((k, vals) -> {
                        if (!HOP_BY_HOP.contains(k.toLowerCase())) {
                            vals.forEach(v -> rb.header(k, v));
                        }
                    });
                    return rb.build();
                })
                .exceptionally(ex ->
                        Response.status(502).entity("Proxy error: " + ex.getMessage()).build());
    }
}
