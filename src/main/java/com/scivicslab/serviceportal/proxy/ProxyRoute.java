package com.scivicslab.serviceportal.proxy;

import com.scivicslab.serviceportal.model.SessionState;
import com.scivicslab.serviceportal.spi.ServiceBackend;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Reverse-proxy route: /proxy/{toolName}/{port}/* → http://localhost:{port}/*
 *
 * Port is embedded in the URL so multiple instances of the same tool
 * can coexist (e.g. two quarkus-chat-ui sessions on :28100 and :28101).
 *
 * Allows access to all tool UIs through a single SSH port forward (28080).
 * HTML responses get a <base> tag injected so relative URLs resolve correctly.
 * SSE / binary responses are streamed through without buffering.
 */
@ApplicationScoped
public class ProxyRoute {

    private static final Set<String> HOP_BY_HOP = Set.of(
        "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
        "te", "trailers", "transfer-encoding", "upgrade", "host");

    @Inject Vertx vertx;
    @Inject ServiceBackend backend;

    private HttpClient httpClient;

    @PostConstruct
    void init() {
        httpClient = vertx.createHttpClient(new HttpClientOptions().setMaxPoolSize(100));
    }

    void addRoutes(@Observes Router router) {
        router.route("/proxy/:toolName/:port/*").handler(this::handleProxy);
        router.route("/proxy/:toolName/:port").handler(this::handleProxy);
    }

    // ---------------------------------------------------------------

    private boolean isValidSession(String toolName, int port) {
        var model = backend.getDashboardModel();
        return model.activeSessions().stream()
            .anyMatch(s -> toolName.equals(s.toolName())
                       && s.port() == port
                       && s.state() == SessionState.READY)
            || model.managementServices().stream()
            .anyMatch(s -> toolName.equals(s.toolName())
                       && s.port() == port
                       && s.state() == SessionState.READY);
    }

    private void handleProxy(RoutingContext ctx) {
        String toolName = ctx.pathParam("toolName");
        String portStr  = ctx.pathParam("port");

        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            ctx.response().setStatusCode(400).end("Invalid port: " + portStr);
            return;
        }

        if (!isValidSession(toolName, port)) {
            ctx.response().setStatusCode(502)
                .end("Tool '" + toolName + "' on port " + port + " is not ready");
            return;
        }

        HttpServerRequest inReq = ctx.request();
        inReq.pause();

        // Strip /proxy/{toolName}/{port} prefix to get the target path
        String mountPoint = "/proxy/" + toolName + "/" + port;
        String path = inReq.path();
        String subPath = path.startsWith(mountPoint) ? path.substring(mountPoint.length()) : "/";
        if (subPath.isEmpty()) subPath = "/";

        String query = inReq.query();
        String targetUri = (query != null && !query.isEmpty()) ? subPath + "?" + query : subPath;

        httpClient.request(inReq.method(), port, "localhost", targetUri)
            .onSuccess(outReq -> {
                inReq.headers().forEach(e -> {
                    if (!HOP_BY_HOP.contains(e.getKey().toLowerCase())) {
                        outReq.putHeader(e.getKey(), e.getValue());
                    }
                });
                outReq.putHeader("host", "localhost:" + port);

                inReq.resume();
                outReq.send(inReq)
                    .onSuccess(outResp -> {
                        ctx.response().setStatusCode(outResp.statusCode());

                        outResp.headers().forEach(e -> {
                            String key = e.getKey().toLowerCase();
                            if (!HOP_BY_HOP.contains(key) && !key.equals("content-length")) {
                                ctx.response().putHeader(e.getKey(), e.getValue());
                            }
                        });

                        String ct = outResp.getHeader("content-type");
                        boolean isHtml = ct != null && ct.contains("text/html");

                        if (!isHtml) {
                            String upstreamLen = outResp.getHeader("content-length");
                            if (upstreamLen != null) {
                                ctx.response().putHeader("content-length", upstreamLen);
                            } else {
                                ctx.response().putHeader("transfer-encoding", "chunked");
                            }
                        }

                        if (isHtml) {
                            outResp.bodyHandler(body -> {
                                String html = body.toString(StandardCharsets.UTF_8);
                                String base = "<base href=\"/proxy/" + toolName + "/" + port + "/\">";
                                String patched = html.replaceFirst("(?i)<head([^>]*)>",
                                    "<head$1>" + base);
                                byte[] bytes = patched.getBytes(StandardCharsets.UTF_8);
                                ctx.response()
                                    .putHeader("content-length", String.valueOf(bytes.length))
                                    .end(Buffer.buffer(bytes));
                            });
                        } else {
                            outResp.pipeTo(ctx.response());
                        }
                    })
                    .onFailure(err -> {
                        if (!ctx.response().ended()) {
                            ctx.response().setStatusCode(502).end(err.getMessage());
                        }
                    });
            })
            .onFailure(err -> {
                inReq.resume();
                if (!ctx.response().ended()) {
                    ctx.response().setStatusCode(502).end(err.getMessage());
                }
            });
    }
}
