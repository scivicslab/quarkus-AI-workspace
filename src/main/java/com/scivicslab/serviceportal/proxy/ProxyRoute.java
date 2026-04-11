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

import java.util.regex.Pattern;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Matcher;

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

    // Additional request headers to strip before forwarding:
    // origin/referer are stripped so upstream CORS filters don't reject the request
    // (from the browser's perspective, everything goes through the same Service Portal origin)
    private static final Set<String> STRIP_REQUEST = Set.of(
        "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
        "te", "trailers", "transfer-encoding", "upgrade", "host",
        "origin", "referer");

    @Inject Vertx vertx;
    @Inject ServiceBackend backend;

    private HttpClient httpClient;

    // Matches absolute-path URLs in HTML attributes: href="/...", src="/...", action="/..."
    // Captures the attribute name and the leading slash to prepend the proxy prefix.
    private static final Pattern ABS_URL_PATTERN = Pattern.compile(
        "((?:href|src|action|data-src)=\")(/)");

    @PostConstruct
    void init() {
        httpClient = vertx.createHttpClient(new HttpClientOptions()
            .setMaxPoolSize(100)
            .setIdleTimeout(0)          // disable idle timeout for long-lived SSE connections
            .setKeepAlive(true));
    }

    void addRoutes(@Observes Router router) {
        router.route("/proxy/:toolName/:port/*").handler(this::handleProxy);
        router.route("/proxy/:toolName/:port").handler(this::handleProxy);
    }

    // ---------------------------------------------------------------

    /**
     * Rewrites absolute-path URLs in HTML attributes to route through the proxy.
     * e.g. href="/foo/bar" → href="/proxy/html-saurus/28110/foo/bar"
     * Leaves protocol-relative (//...) and full URLs (http://...) untouched.
     */
    private static String rewriteAbsoluteUrls(String html, String proxyBase) {
        Matcher m = ABS_URL_PATTERN.matcher(html);
        StringBuilder sb = new StringBuilder(html.length() + 64);
        while (m.find()) {
            m.appendReplacement(sb, m.group(1) + Matcher.quoteReplacement(proxyBase) + "/");
        }
        m.appendTail(sb);
        return sb.toString();
    }

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
                    if (!STRIP_REQUEST.contains(e.getKey().toLowerCase())) {
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
                        boolean isSse  = ct != null && ct.contains("text/event-stream");

                        if (!isHtml) {
                            String upstreamLen = outResp.getHeader("content-length");
                            if (upstreamLen != null && !isSse) {
                                ctx.response().putHeader("content-length", upstreamLen);
                            } else {
                                ctx.response().putHeader("transfer-encoding", "chunked");
                            }
                        }

                        if (isHtml) {
                            outResp.bodyHandler(body -> {
                                String html = body.toString(StandardCharsets.UTF_8);
                                String proxyBase = "/proxy/" + toolName + "/" + port;
                                // Rewrite absolute-path URLs BEFORE injecting <base> tag,
                                // so the injected tag itself is not re-rewritten.
                                String patched = rewriteAbsoluteUrls(html, proxyBase);
                                // Inject <base> tag so relative URLs resolve through the proxy
                                String base = "<base href=\"" + proxyBase + "/\">";
                                patched = patched.replaceFirst("(?i)<head([^>]*)>",
                                    "<head$1>" + base);
                                byte[] bytes = patched.getBytes(StandardCharsets.UTF_8);
                                ctx.response()
                                    .putHeader("content-length", String.valueOf(bytes.length))
                                    .end(Buffer.buffer(bytes));
                            });
                        } else {
                            // SSE and other streaming responses: pipe without buffering
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
