package com.jdesk.frame.webview.resource.http;

import com.jdesk.frame.webview.resource.WebResourceHandler;
import com.jdesk.frame.webview.resource.WebResourceRequest;
import com.jdesk.frame.webview.resource.WebResourceResponse;
import com.jdesk.frame.webview.resource.WebResourceSchemeRegistry;
import com.jdesk.frame.webview.resource.WebResourceUploadFile;
import com.jdesk.frame.webview.resource.WebResourceUploadForm;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Starts a real loopback HTTP server; needs an environment where the JVM can open sockets. */
class WebResourceServerTest {

    private static final String PUBLIC_HOST = "myapp.localhost";
    private static final String HANDOFF_PATH = "/file/record/dropped-files";

    @TempDir
    Path dir;

    private final WebResourceSchemeRegistry registry = new WebResourceSchemeRegistry();
    private final AtomicReference<WebResourceRequest> seen = new AtomicReference<>();
    private WebResourceServer server;
    private HttpClient client;

    @BeforeEach
    void setUp() throws Exception {
        Path file = dir.resolve("a.bin");
        Files.write(file, new byte[1000]);
        Path page = dir.resolve("page.html");
        Files.write(page, "<p>hi</p>".getBytes(StandardCharsets.UTF_8));
        WebResourceHandler fileHandler = request -> {
            seen.set(request);
            if ("/a.bin".equals(request.uri().getPath())) {
                return CompletableFuture.completedFuture(WebResourceResponse.ofFile("application/octet-stream", file));
            }
            // Content-Type by extension, since hardening depends on MIME; /disk/ uses the file response path.
            String path = request.uri().getPath();
            boolean onDisk = "/page.html".equals(path) || path.startsWith("/disk/");
            String typed = typeOf(path);
            if (typed != null) {
                return CompletableFuture.completedFuture(onDisk
                        ? WebResourceResponse.ofFile(typed, page)
                        : WebResourceResponse.ok(typed, "x".getBytes(StandardCharsets.UTF_8)));
            }
            if (path.endsWith(".untyped")) {
                return CompletableFuture.completedFuture(onDisk
                        ? WebResourceResponse.ofFile(200, Map.of(), page)
                        : WebResourceResponse.of(200, Map.of(), "x".getBytes(StandardCharsets.UTF_8)));
            }
            if ("POST".equals(request.method())) {
                String text = "files=" + request.uploadFiles().size() + " len=" + request.body().length;
                return CompletableFuture.completedFuture(WebResourceResponse.ok("text/plain",
                        text.getBytes(StandardCharsets.UTF_8)));
            }
            return CompletableFuture.completedFuture(WebResourceResponse.ok("text/plain",
                    ("path=" + request.uri().getPath()).getBytes(StandardCharsets.UTF_8)));
        };
        registry.register("file", fileHandler);
        registry.register("app", fileHandler);
        server = new WebResourceServer(registry, PUBLIC_HOST, new WebResourceServer.Config(1, 4, Duration.ofSeconds(30),
                4, 1024, Duration.ofSeconds(1), Duration.ofMillis(500), HANDOFF_PATH));
        server.start();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        server.dispose();
    }

    /** Maps a test extension to its Content-Type, or null for the plain-text echo. */
    private static String typeOf(String path) {
        if (path.endsWith(".html")) {
            return "text/html; charset=UTF-8";
        }
        if (path.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (path.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (path.endsWith(".rss")) {
            return "application/rss+xml";
        }
        if (path.endsWith(".js")) {
            return "application/javascript; charset=UTF-8";
        }
        if (path.endsWith(".mjs")) {
            return "text/javascript";
        }
        if (path.endsWith(".css")) {
            return "text/css; charset=UTF-8";
        }
        if (path.endsWith(".wasm")) {
            return "application/wasm";
        }
        return null;
    }

    private String cookie() {
        return WebResourceAuth.COOKIE_NAME + "=" + server.session().cookieValue();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(server.bindBaseUrl() + path)).header("Cookie", cookie());
    }

    private HttpResponse<byte[]> send(HttpRequest.Builder builder) throws Exception {
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    /**
     * Sends a raw socket request, since HttpClient can't set Host and Chromium's proxied requests use an absolute
     * request line with the public host.
     */
    private String raw(String request) throws IOException {
        try (Socket socket = new Socket(WebResourceAuth.BIND_HOST, server.port())) {
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    @Test
    void routesToHandlerAndAddsHardeningHeaders() throws Exception {
        HttpResponse<byte[]> response = send(request("/file/record/images/x.png?v=1"));

        assertEquals(200, response.statusCode());
        assertEquals("path=/images/x.png", new String(response.body(), StandardCharsets.UTF_8));
        assertEquals("record", seen.get().uri().getHost());
        assertEquals("v=1", seen.get().uri().getQuery());
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElseThrow());
        // Don't change to same-origin: browsers would drop styles, scripts and images in sandboxed iframes.
        assertEquals("cross-origin", response.headers().firstValue("Cross-Origin-Resource-Policy").orElseThrow());
        assertEquals("no-cache", response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(response.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
    }

    /**
     * A {@code %} in a file name arrives as {@code %25}, which Jetty rejects by default as ambiguous path encoding.
     * Allowing it is safe because paths are decoded once, by {@code WebResourceRoute} per segment.
     */
    @Test
    void servesFileNamesContainingPercentAndHash() throws Exception {
        HttpResponse<byte[]> percent = send(request("/file/record/docs/100%25.xlsx"));
        assertEquals(200, percent.statusCode(), new String(percent.body(), StandardCharsets.UTF_8));
        assertEquals("/docs/100%.xlsx", seen.get().uri().getPath());

        assertEquals(200, send(request("/file/record/%E6%8A%A5%E5%91%8A%20%233.pdf")).statusCode());
        assertEquals("/报告 #3.pdf", seen.get().uri().getPath());
    }

    /** HTML or SVG under /file must be sandboxed; served plainly it would run script with the app origin. */
    @Test
    void sandboxesActiveContentOutsideTheAppName() throws Exception {
        assertEquals("sandbox", csp(send(request("/file/record/x.html"))));
        assertEquals("sandbox", csp(send(request("/file/record/x.svg"))));
        // File responses take another write path (ranges, conditional requests); cover it too.
        assertEquals("sandbox", csp(send(request("/file/record/page.html"))));
        // Allow-listed: unknown XML types and a missing Content-Type are sandboxed too, on both write paths.
        assertEquals("sandbox", csp(send(request("/file/record/feed.rss"))));
        assertEquals("sandbox", csp(send(request("/file/record/x.untyped"))));
        assertEquals("sandbox", csp(send(request("/file/record/disk/x.untyped"))));
        // PDF, images, byte streams and plain text aren't sandboxed, so a built-in PDF viewer can still use them.
        assertNull(csp(send(request("/file/record/x.pdf"))));
        assertNull(csp(send(request("/file/record/x.png"))));
        assertNull(csp(send(request("/file/record/a.bin"))));
        assertNull(csp(send(request("/file/record/x"))));
        // The app's own UI keeps the app origin's permissions.
        assertNull(csp(send(request("/app/main/index.html"))));
        assertNull(csp(send(request("/app/main/x.untyped"))));
    }

    private static String csp(HttpResponse<byte[]> response) {
        return response.headers().firstValue("Content-Security-Policy").orElse(null);
    }

    /**
     * Scripts, styles and wasm from user folders are served with an inert type; with nosniff, a page can't load them
     * as same-origin code.
     */
    @Test
    void executableTypesOutsideTheAppNameAreServedInert() throws Exception {
        assertEquals("text/plain", mime(send(request("/file/record/x.js"))));
        assertEquals("text/plain", mime(send(request("/file/record/x.mjs"))));
        assertEquals("text/plain", mime(send(request("/file/record/x.css"))));
        assertEquals("application/octet-stream", mime(send(request("/file/record/x.wasm"))));
        assertEquals("text/plain", mime(send(request("/file/record/disk/x.js"))));
        assertEquals("application/javascript", mime(send(request("/app/main/x.js"))));
        assertEquals("text/css", mime(send(request("/app/main/disk/x.css"))));
    }

    private static String mime(HttpResponse<byte[]> response) {
        String type = response.headers().firstValue("Content-Type").orElse("");
        int semicolon = type.indexOf(';');
        return (semicolon < 0 ? type : type.substring(0, semicolon)).trim().toLowerCase(Locale.ROOT);
    }

    /** The session token is only for authentication; a handler could log it, so it never sees the cookie. */
    @Test
    void handlerNeverSeesTheSessionCookie() throws Exception {
        assertEquals(200, send(request("/file/record/x")).statusCode());
        assertTrue(seen.get().headers().keySet().stream().noneMatch("Cookie"::equalsIgnoreCase), seen.get().headers().toString());
    }

    /** The proxied Chromium form: absolute request line, the public Host without a port, and Proxy-Connection. */
    @Test
    void absoluteFormRequestWithPublicHostIsServed() throws Exception {
        String response = raw("GET http://myapp.localhost/file/record/images/x.png?v=2 HTTP/1.1\r\n"
                + "Host: myapp.localhost\r\nProxy-Connection: keep-alive\r\nCookie: " + cookie() + "\r\n"
                + "Sec-Fetch-Site: same-origin\r\nSec-Fetch-Mode: no-cors\r\nConnection: close\r\n\r\n");

        assertTrue(response.startsWith("HTTP/1.1 200 "), response);
        assertTrue(response.endsWith("path=/images/x.png"), response);
        assertEquals("record", seen.get().uri().getHost());
        assertEquals("v=2", seen.get().uri().getQuery());

        String foreign = raw("GET http://evil.localhost/file/record/x HTTP/1.1\r\nHost: evil.localhost\r\n"
                + "Cookie: " + cookie() + "\r\nConnection: close\r\n\r\n");
        assertTrue(foreign.startsWith("HTTP/1.1 403 "), foreign);
    }

    @Test
    void sessionCarriesPublicOriginBindAddressAndProxyRules() {
        WebResourceSession session = server.session();
        assertEquals("http://myapp.localhost", server.publicBaseUrl());
        assertEquals(server.publicBaseUrl(), session.publicBaseUrl());
        assertEquals(server.bindBaseUrl(), session.bindBaseUrl());
        assertEquals("http://127.0.0.1:" + server.port(), session.bindBaseUrl());
        assertEquals("http=127.0.0.1:" + server.port(), session.proxyRules());
        assertEquals("<-loopback>;localhost;127.0.0.1", session.proxyBypassRules());
        assertEquals(PUBLIC_HOST, session.cookieDomain());
        assertEquals(server.publicBaseUrl() + HANDOFF_PATH, session.pathHandoffUrl(), "the public handoff URL");
        assertFalse(session.uploadToken().isBlank());
        assertNotEquals(session.cookieValue(), session.uploadToken(), "the handoff token and session cookie differ");
        assertFalse(session.toString().contains(session.uploadToken()), "neither token appears in toString");
        assertFalse(session.toString().contains(session.cookieValue()));
        assertThrows(IllegalArgumentException.class, () -> new WebResourceServer(registry, "myapp.app"));
    }

    @Test
    void rejectsWithoutCookieWithWrongHostForeignOriginAndCrossSite() throws Exception {
        assertEquals(403, send(HttpRequest.newBuilder(URI.create(server.bindBaseUrl() + "/file/record/x"))).statusCode());
        assertEquals(403, send(request("/file/record/x").header("Origin", "http://evil.example")).statusCode());
        assertEquals(403, send(request("/file/record/x").header("Sec-Fetch-Site", "cross-site")
                .header("Sec-Fetch-Mode", "cors")).statusCode());
        assertEquals(403, send(request("/file/record/x").header("Sec-Fetch-Site", "cross-site")
                .header("Sec-Fetch-Mode", "navigate")).statusCode());
        assertEquals(200, send(request("/file/record/x").header("Sec-Fetch-Site", "none")
                .header("Origin", server.bindBaseUrl())).statusCode());
        assertEquals(200, send(request("/file/record/x").header("Sec-Fetch-Site", "same-origin")
                .header("Origin", server.publicBaseUrl())).statusCode());
    }

    /** Sandboxed iframe reads (cookie, no Origin, no-cors, cross-site) pass; a POST of the same shape doesn't. */
    @Test
    void sandboxedNoCorsReadsPassAndWritesDoNot() throws Exception {
        assertEquals(200, send(request("/file/record/a.bin").header("Sec-Fetch-Site", "cross-site")
                .header("Sec-Fetch-Mode", "no-cors").header("Sec-Fetch-Dest", "image")).statusCode());
        assertEquals(403, send(request("/file/record/images/").header("Sec-Fetch-Site", "cross-site")
                .header("Sec-Fetch-Mode", "no-cors")
                .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[] {1}))).statusCode());
    }

    @Test
    void unknownNameRootTraversalAndOptions() throws Exception {
        assertEquals(404, send(request("/nope/record/x")).statusCode());
        assertEquals(404, send(request("/")).statusCode());
        assertEquals(400, send(request("/file/record/..%2Fx")).statusCode());
        assertEquals(405, send(request("/file/record/x").method("OPTIONS", HttpRequest.BodyPublishers.noBody())).statusCode());
    }

    @Test
    void fileResponsesSupportRangeConditionalAndHead() throws Exception {
        HttpResponse<byte[]> full = send(request("/file/record/a.bin"));
        assertEquals(200, full.statusCode());
        assertEquals(1000, full.body().length);
        assertEquals("bytes", full.headers().firstValue("Accept-Ranges").orElseThrow());
        String etag = full.headers().firstValue("ETag").orElseThrow();

        HttpResponse<byte[]> part = send(request("/file/record/a.bin").header("Range", "bytes=10-19"));
        assertEquals(206, part.statusCode());
        assertEquals(10, part.body().length);
        assertEquals("bytes 10-19/1000", part.headers().firstValue("Content-Range").orElseThrow());

        assertEquals(416, send(request("/file/record/a.bin").header("Range", "bytes=5000-")).statusCode());
        assertEquals(304, send(request("/file/record/a.bin").header("If-None-Match", etag)).statusCode());

        HttpResponse<byte[]> head = send(request("/file/record/a.bin").method("HEAD", HttpRequest.BodyPublishers.noBody()));
        assertEquals(200, head.statusCode());
        assertEquals(0, head.body().length);
        assertEquals("1000", head.headers().firstValue("Content-Length").orElseThrow());
    }

    @Test
    void uploadFormIsDecodedIntoUploadFilesOnlyWithTheSessionToken() throws Exception {
        String listed = WebResourceUploadForm.FIELD + "=" + URLEncoder.encode(
                WebResourceUploadForm.encode(List.of(new WebResourceUploadFile("a.txt", "C:\\a.txt"))),
                StandardCharsets.UTF_8);
        String token = server.session().uploadToken();

        assertEquals(200, postForm(listed + "&" + WebResourceUploadForm.TOKEN_FIELD + "=" + token).statusCode());
        assertEquals("C:\\a.txt", seen.get().uploadFiles().get(0).path());
        assertFalse(new String(seen.get().body(), StandardCharsets.UTF_8).contains(token), "no token for the handler");

        // Page script can POST its own form here too, but it doesn't have the token.
        assertEquals(200, postForm(listed).statusCode());
        assertEquals(List.of(), seen.get().uploadFiles());
        assertEquals(200, postForm(listed + "&" + WebResourceUploadForm.TOKEN_FIELD + "=guess").statusCode());
        assertEquals(List.of(), seen.get().uploadFiles());
    }

    private HttpResponse<byte[]> postForm(String form) throws Exception {
        return send(request(HANDOFF_PATH).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)));
    }

    @Test
    void bodyOverLimitIs413BeforeHandler() throws Exception {
        seen.set(null);
        HttpResponse<byte[]> response = send(request("/file/record/images/")
                .header("Content-Type", "image/png")
                .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[1025])));

        assertEquals(413, response.statusCode());
        assertNull(seen.get(), "an oversized request must not reach the handler");
    }

    /** Forms are read into memory, so their 1 MiB limit is tighter than the body limit: a 2 MiB form gets 413. */
    @Test
    void oversizeFormIs413EvenWhenTheBodyLimitIsGenerous() throws Exception {
        WebResourceServer generous = new WebResourceServer(registry, PUBLIC_HOST, WebResourceServer.Config.defaults());
        generous.start();
        try {
            seen.set(null);
            HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(
                            URI.create(generous.bindBaseUrl() + HANDOFF_PATH))
                            .header("Cookie", WebResourceAuth.COOKIE_NAME + "=" + generous.session().cookieValue())
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString("k=" + "x".repeat(2 * 1024 * 1024)))
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());

            assertEquals(413, response.statusCode());
            assertNull(seen.get(), "an oversized form must not reach the handler");
        } finally {
            generous.dispose();
        }
    }

    /** A handler future that never completes times out with 504 and frees its worker for later requests. */
    @Test
    void aHandlerThatNeverAnswersTimesOutInsteadOfHoldingAWorker() throws Exception {
        registry.register("stuck", request -> new CompletableFuture<>());

        for (int i = 0; i < 3; i++) {
            HttpResponse<byte[]> stuck = send(request("/stuck/record/x").timeout(Duration.ofSeconds(10)));
            assertEquals(504, stuck.statusCode());
        }
        assertEquals(200, send(request("/file/record/x").timeout(Duration.ofSeconds(10))).statusCode());
    }

    @Test
    void disposeClosesThePort() {
        String base = server.bindBaseUrl();
        server.dispose();
        assertFalse(server.isRunning());
        HttpClient fresh = HttpClient.newHttpClient();
        assertThrows(IOException.class, () -> fresh.send(
                HttpRequest.newBuilder(URI.create(base + "/file/record/x")).build(),
                HttpResponse.BodyHandlers.discarding()));
    }
}
