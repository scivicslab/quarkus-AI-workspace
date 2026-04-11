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

                        String proxyBase = "/proxy/" + toolName + "/" + port;

                        outResp.headers().forEach(e -> {
                            String key = e.getKey().toLowerCase();
                            if (!HOP_BY_HOP.contains(key) && !key.equals("content-length")) {
                                // Rewrite server-side redirect Location headers so the browser
                                // stays within the proxy (e.g. 302 Location: /foo → /proxy/t/p/foo).
                                if (key.equals("location")) {
                                    String loc = e.getValue();
                                    if (loc != null && loc.startsWith("/") && !loc.startsWith(proxyBase)) {
                                        ctx.response().putHeader(e.getKey(), proxyBase + loc);
                                    } else {
                                        ctx.response().putHeader(e.getKey(), loc);
                                    }
                                } else {
                                    ctx.response().putHeader(e.getKey(), e.getValue());
                                }
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

                                // Step 1: Rewrite absolute-path URLs in HTML attributes
                                // (href, src, action, data-src) before injecting the <base>
                                // tag so the injected markup is not re-processed.
                                String patched = rewriteAbsoluteUrls(html, proxyBase);

                                // Step 2: Inject <base> tag using the request URL's own
                                // directory so that relative links (e.g. ../../foo) resolve
                                // correctly regardless of how deeply nested the page is within
                                // the portal hierarchy.
                                String reqPath = inReq.path();
                                int lastSlash = reqPath.lastIndexOf('/');
                                String baseDir = lastSlash >= 0
                                    ? reqPath.substring(0, lastSlash + 1)
                                    : proxyBase + "/";
                                String base = "<base href=\"" + baseDir + "\">";

                                // Step 3: Inject a monkey-patch script that rewrites
                                // absolute-path URLs in programmatic navigation so that
                                // fetch(), XMLHttpRequest, location.assign(), and
                                // location.replace() all go through the proxy without
                                // requiring any changes in the backend tool.
                                String pb = proxyBase; // effectively final for use in string
                                String script = "<script>(function(){"
                                    + "var b='" + pb + "';"
                                    + "var pf=window.fetch;"
                                    + "window.fetch=function(u,o){"
                                    + "if(typeof u==='string'&&u.charCodeAt(0)===47&&!u.startsWith(b))u=b+u;"
                                    + "return pf.call(this,u,o);};"
                                    + "var px=XMLHttpRequest.prototype.open;"
                                    + "XMLHttpRequest.prototype.open=function(m,u){"
                                    + "if(typeof u==='string'&&u.charCodeAt(0)===47&&!u.startsWith(b))u=b+u;"
                                    + "return px.apply(this,arguments);};"
                                    + "var pa=location.assign.bind(location);"
                                    + "location.assign=function(u){"
                                    + "if(u.charCodeAt(0)===47&&!u.startsWith(b))u=b+u;pa(u);};"
                                    + "var pr=location.replace.bind(location);"
                                    + "location.replace=function(u){"
                                    + "if(u.charCodeAt(0)===47&&!u.startsWith(b))u=b+u;pr(u);};"
                                    + "})();</script>";

                                patched = patched.replaceFirst("(?i)<head([^>]*)>",
                                    "<head$1>" + base + script);
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
