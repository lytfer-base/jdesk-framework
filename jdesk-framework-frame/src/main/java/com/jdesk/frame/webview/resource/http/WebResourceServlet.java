package com.jdesk.frame.webview.resource.http;

import com.jdesk.frame.webview.resource.WebResourceRequest;
import com.jdesk.frame.webview.resource.WebResourceResponse;
import com.jdesk.frame.webview.resource.WebResourceRoute;
import com.jdesk.frame.webview.resource.WebResourceSchemeRegistry;
import com.jdesk.frame.webview.resource.WebResourceUploadFile;
import com.jdesk.frame.webview.resource.WebResourceUploadForm;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Servlet for the resource server: authenticates, routes, dispatches to the registry and writes the response,
 * streaming files per {@link FileResponsePlan}. Handlers are awaited on a Jetty worker thread, bounded with a 504 on
 * timeout, and may block on I/O. Don't touch the EDT from a handler.
 */
final class WebResourceServlet extends HttpServlet {

    private static final Logger LOG = LoggerFactory.getLogger(WebResourceServlet.class);
    private static final int WARN_REJECTIONS = 5;
    private static final int CHUNK = 64 * 1024;
    /** A form is read into memory whole, so its cap is far tighter than the streaming body limit. */
    private static final long MAX_FORM_BYTES = 1024L * 1024;
    /** MIME essences the HTML standard treats as JavaScript. */
    private static final Set<String> SCRIPT_TYPES = Set.of("application/ecmascript", "application/javascript",
            "application/x-ecmascript", "application/x-javascript", "text/ecmascript", "text/javascript",
            "text/javascript1.0", "text/javascript1.1", "text/javascript1.2", "text/javascript1.3",
            "text/javascript1.4", "text/javascript1.5", "text/jscript", "text/livescript", "text/x-ecmascript",
            "text/x-javascript");
    /** Inert types served without sandbox, besides all of image/ (except SVG), audio/, video/ and font/. */
    private static final Set<String> INERT_TYPES = Set.of("application/pdf", "application/octet-stream",
            "application/zip", "application/json", "text/plain", "text/csv");

    private final transient WebResourceSchemeRegistry registry;
    private final transient WebResourceAuth auth;
    private final transient String uploadToken;
    private final long maxBodyBytes;
    private final transient Duration handlerTimeout;
    private final AtomicInteger rejections = new AtomicInteger();

    /** @param uploadToken hand-off token that a {@link WebResourceUploadForm} path list must carry to count */
    WebResourceServlet(WebResourceSchemeRegistry registry, WebResourceAuth auth, String uploadToken,
                       long maxBodyBytes, Duration handlerTimeout) {
        this.registry = registry;
        this.auth = auth;
        this.uploadToken = uploadToken;
        this.maxBodyBytes = maxBodyBytes;
        this.handlerTimeout = handlerTimeout;
    }

    /**
     * Reissues the session cookie as {@code SameSite=None; Secure} on document requests only. Backends can seed it
     * only without SameSite, which means Lax, so a sandboxed iframe's subresource requests would go without it.
     */
    private void upgradeSessionCookie(HttpServletRequest req, HttpServletResponse resp) {
        String dest = req.getHeader("Sec-Fetch-Dest");
        if (dest != null && !"document".equalsIgnoreCase(dest.trim()) && !"iframe".equalsIgnoreCase(dest.trim())) {
            return;
        }
        resp.addHeader("Set-Cookie", WebResourceAuth.COOKIE_NAME + "=" + auth.cookieValue()
                + "; Path=/; HttpOnly; Secure; SameSite=None");
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setHeader("X-Content-Type-Options", "nosniff");
        resp.setHeader("Referrer-Policy", "same-origin");
        // Must be cross-origin: a sandboxed iframe has an opaque origin, and same-origin would drop its subresources.
        resp.setHeader("Cross-Origin-Resource-Policy", "cross-origin");

        Optional<String> rejected = auth.reject(req.getMethod(), req.getHeader("Host"), req.getHeader("Cookie"),
                req.getHeader("Origin"), req.getHeader("Sec-Fetch-Site"), req.getHeader("Sec-Fetch-Mode"));
        if (rejected.isPresent()) {
            int count = rejections.incrementAndGet();
            if (count <= WARN_REJECTIONS) {
                LOG.warn("web.resources.rejected reason={} method={} path={} remotePort={}",
                        rejected.get(), req.getMethod(), req.getRequestURI(), req.getRemotePort());
            } else {
                LOG.debug("web.resources.rejected reason={} path={}", rejected.get(), req.getRequestURI());
            }
            resp.setStatus(403);
            resp.setContentLength(0);
            return;
        }
        upgradeSessionCookie(req, resp);
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            resp.setStatus(405);
            resp.setContentLength(0);
            return;
        }

        Optional<WebResourceRoute> route;
        try {
            route = WebResourceRoute.parse(req.getRequestURI(), req.getQueryString());
        } catch (IllegalArgumentException invalid) {
            writeText(resp, 400, invalid.getMessage());
            return;
        }
        if (route.isEmpty() || !registry.hasHandler(route.get().name())) {
            writeText(resp, 404, "No resource at this path.");
            return;
        }
        if (req.getContentLengthLong() > maxBodyBytes) {
            writeText(resp, 413, "Request body exceeds the limit.");
            return;
        }
        WebResourceRoute matched = route.get();
        // The app's own UI gets the app origin's rights; other names serve user files, demoted and sandboxed.
        boolean untrusted = !"app".equals(matched.name());

        WebResourceRequest request;
        try {
            request = toRequest(req, matched);
        } catch (BodyTooLargeException tooLarge) {
            writeText(resp, 413, tooLarge.getMessage());
            return;
        }
        CompletableFuture<WebResourceResponse> pending = registry.dispatch(request).toCompletableFuture();
        WebResourceResponse response;
        try {
            response = pending.get(handlerTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            pending.cancel(true);
            LOG.warn("web.resources.handlerTimeout path={} timeoutMs={}", req.getRequestURI(), handlerTimeout.toMillis());
            writeText(resp, 504, "Resource handler did not respond in time.");
            return;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            writeText(resp, 500, "Interrupted.");
            return;
        } catch (ExecutionException failure) {
            writeText(resp, 500, String.valueOf(failure.getCause()));
            return;
        }
        write(req, resp, response, untrusted);
    }

    private WebResourceRequest toRequest(HttpServletRequest req, WebResourceRoute route) throws IOException {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String name : Collections.list(req.getHeaderNames())) {
            // Don't pass Cookie to handlers: it holds the session token.
            if (!"Cookie".equalsIgnoreCase(name)) {
                headers.put(name, new ArrayList<>(Collections.list(req.getHeaders(name))));
            }
        }
        String contentType = req.getContentType();
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded")) {
            if (req.getContentLengthLong() > MAX_FORM_BYTES) {
                throw new BodyTooLargeException();
            }
            byte[] form = new BoundedInputStream(req.getInputStream(), MAX_FORM_BYTES).readAllBytes();
            List<WebResourceUploadFile> files = WebResourceUploadForm.decode(contentType, form, uploadToken);
            // Don't pass the hand-off token to handlers either.
            return new WebResourceRequest(req.getMethod(), route.uri(), headers,
                    WebResourceRoute.resourceTypeOf(req.getHeader("Sec-Fetch-Dest")), "",
                    WebResourceUploadForm.withoutToken(form), files);
        }
        return WebResourceRequest.streaming(req.getMethod(), route.uri(), headers,
                WebResourceRoute.resourceTypeOf(req.getHeader("Sec-Fetch-Dest")), "",
                new BoundedInputStream(req.getInputStream(), maxBodyBytes),
                req.getContentLengthLong(), List.of());
    }

    private static void write(HttpServletRequest req, HttpServletResponse resp, WebResourceResponse response,
                              boolean untrusted) throws IOException {
        boolean head = "HEAD".equalsIgnoreCase(req.getMethod());
        Optional<Path> file = response.file();
        if (file.isPresent()) {
            writeFile(req, resp, response, file.get(), untrusted);
            return;
        }
        resp.setStatus(response.statusCode());
        Map<String, String> headers = responseHeaders(response, untrusted);
        boolean cacheControl = false;
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if ("Content-Length".equalsIgnoreCase(header.getKey())) {
                continue;
            }
            cacheControl |= "Cache-Control".equalsIgnoreCase(header.getKey());
            resp.setHeader(header.getKey(), header.getValue());
        }
        if (!cacheControl) {
            resp.setHeader("Cache-Control", "no-cache");
        }
        sandbox(resp, untrusted, contentTypeOf(headers));
        byte[] body = response.body();
        resp.setContentLengthLong(body.length);
        if (!head && body.length > 0) {
            resp.getOutputStream().write(body);
        }
    }

    private static void writeFile(HttpServletRequest req, HttpServletResponse resp, WebResourceResponse response,
                                  Path file, boolean untrusted) throws IOException {
        if (!Files.isRegularFile(file)) {
            writeText(resp, 404, "File not found.");
            return;
        }
        long size = Files.size(file);
        long mtime = Files.getLastModifiedTime(file).toMillis();
        Map<String, String> handlerHeaders = responseHeaders(response, untrusted);
        handlerHeaders.putIfAbsent("Cache-Control", "no-cache");
        FileResponsePlan plan = FileResponsePlan.of(req.getMethod(), size, mtime, handlerHeaders,
                req.getHeader("Range"), req.getHeader("If-None-Match"), req.getHeader("If-Modified-Since"));
        if (!plan.writeBody()) {
            writeFileHead(resp, plan, handlerHeaders, untrusted);
            return;
        }
        // Open the file before writing the status, so a failure can still change it instead of sending a short 200.
        FileChannel opened;
        try {
            opened = FileChannel.open(file, StandardOpenOption.READ);
        } catch (NoSuchFileException gone) {
            writeText(resp, 404, "File not found.");
            return;
        } catch (IOException unreadable) {
            LOG.warn("web.resources.file.unreadable path={}", file, unreadable);
            writeText(resp, 500, "Failed to open file: " + unreadable);
            return;
        }
        writeFileHead(resp, plan, handlerHeaders, untrusted);
        try (FileChannel channel = opened;
             OutputStream out = resp.getOutputStream()) {
            channel.position(plan.offset());
            ByteBuffer buffer = ByteBuffer.allocate(CHUNK);
            byte[] copy = new byte[CHUNK];
            long remaining = plan.length();
            while (remaining > 0) {
                buffer.clear();
                buffer.limit((int) Math.min(CHUNK, remaining));
                int read = channel.read(buffer);
                if (read < 0) {
                    break;
                }
                buffer.flip();
                buffer.get(copy, 0, read);
                out.write(copy, 0, read);
                remaining -= read;
            }
        } catch (IOException disconnected) {
            // The response is committed; usually the client hung up, as pdf.js does after probing Range support.
            LOG.debug("web.resources.file.aborted path={}", file, disconnected);
        }
    }

    /** Writes the status, the plan's headers and the sandbox; after this, only resetBuffer can change the status. */
    private static void writeFileHead(HttpServletResponse resp, FileResponsePlan plan,
                                      Map<String, String> handlerHeaders, boolean untrusted) {
        resp.setStatus(plan.status());
        for (Map.Entry<String, String> header : plan.headers().entrySet()) {
            resp.setHeader(header.getKey(), header.getValue());
        }
        // Judge by the handler's declared type: a 304 plan has no Content-Type.
        sandbox(resp, untrusted, contentTypeOf(handlerHeaders));
    }

    /** Mutable copy of the handler's headers; outside app, scripts and styles become text/plain and wasm bytes. */
    private static Map<String, String> responseHeaders(WebResourceResponse response, boolean untrusted) {
        Map<String, String> headers = new LinkedHashMap<>(response.headers());
        if (untrusted) {
            headers.replaceAll((name, value) -> "Content-Type".equalsIgnoreCase(name) ? inertType(value) : value);
        }
        return headers;
    }

    private static String inertType(String contentType) {
        String essence = essenceOf(contentType);
        if (SCRIPT_TYPES.contains(essence) || "text/css".equals(essence)) {
            int semicolon = contentType.indexOf(';');
            return "text/plain" + (semicolon < 0 ? "" : contentType.substring(semicolon));
        }
        return "application/wasm".equals(essence) ? "application/octet-stream" : contentType;
    }

    /**
     * Adds {@code Content-Security-Policy: sandbox} outside app for any type that isn't inert, missing ones included,
     * so a page opened there gets an opaque origin without the bridge or cookies.
     * Keep PDF in the inert list: the built-in PDF viewer depends on it.
     */
    private static void sandbox(HttpServletResponse resp, boolean untrusted, String contentType) {
        if (untrusted && !isInert(contentType)) {
            resp.setHeader("Content-Security-Policy", "sandbox");
        }
    }

    /** Returns whether a page of this type can't run scripts even when opened directly. */
    private static boolean isInert(String contentType) {
        if (contentType == null) {
            return false;
        }
        String essence = essenceOf(contentType);
        if (essence.startsWith("image/")) {
            return !"image/svg+xml".equals(essence);
        }
        return essence.startsWith("audio/") || essence.startsWith("video/") || essence.startsWith("font/")
                || INERT_TYPES.contains(essence);
    }

    /** Returns the lower-case MIME essence without parameters. */
    private static String essenceOf(String contentType) {
        int semicolon = contentType.indexOf(';');
        return (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).trim().toLowerCase(Locale.ROOT);
    }

    private static String contentTypeOf(Map<String, String> headers) {
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if ("Content-Type".equalsIgnoreCase(header.getKey())) {
                return header.getValue();
            }
        }
        return null;
    }

    private static void writeText(HttpServletResponse resp, int status, String message) throws IOException {
        byte[] body = (message == null ? "" : message).getBytes(StandardCharsets.UTF_8);
        resp.setStatus(status);
        resp.setHeader("Content-Type", "text/plain; charset=UTF-8");
        resp.setHeader("Cache-Control", "no-cache");
        resp.setContentLengthLong(body.length);
        resp.getOutputStream().write(body);
    }

    /** Throws once reads pass the limit, which catches chunked uploads without a Content-Length. */
    private static final class BoundedInputStream extends FilterInputStream {
        private long remaining;

        BoundedInputStream(InputStream in, long limit) {
            super(in);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0 && --remaining < 0) {
                throw new BodyTooLargeException();
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                remaining -= read;
                if (remaining < 0) {
                    throw new BodyTooLargeException();
                }
            }
            return read;
        }
    }

    /** Request body over the limit: a 413 when the servlet reads it, thrown on to a handler reading the stream. */
    private static final class BodyTooLargeException extends IOException {
        BodyTooLargeException() {
            super("Request body exceeds the limit.");
        }
    }
}
