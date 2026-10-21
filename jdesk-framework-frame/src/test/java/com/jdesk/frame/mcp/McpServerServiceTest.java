package com.jdesk.frame.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdesk.core.app.PathManager;
import com.jdesk.core.runtime.RuntimeParamRegistry;
import com.jdesk.core.runtime.RuntimeParamSource;
import com.jdesk.frame.runtime.params.FrameRuntimeParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavior tests for McpServerService over a real loopback HTTP server: a full MCP session of initialize, tools/list
 * and tools/call, and a closed port after dispose. The Host / Origin checks and the body limit are tested with raw
 * socket requests.
 */
class McpServerServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Home points to a temp directory because start writes a port file. */
    @TempDir
    Path homeDir;

    private McpServerService service;

    @BeforeEach
    void setUp() {
        PathManager.setHomePathForTests(homeDir);
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.dispose();
        }
        PathManager.resetForTests();
        RuntimeParamRegistry.reset();
    }

    /** The port file lets the stdio bridge find the server: start writes the URL into it and dispose deletes it. */
    @Test
    void startWritesPortFileAndDisposeDeletesIt() throws Exception {
        service = newService();
        service.start();

        Path portFile = PathManager.getSystemDir().resolve("mcp/mcp-test-server.url");
        assertTrue(Files.isRegularFile(portFile), "the port file must exist after start");
        assertEquals(service.getUrl(), Files.readString(portFile).trim());

        service.dispose();
        assertFalse(Files.exists(portFile), "the port file must be deleted on dispose");
        service = null; // Already disposed; tearDown must not repeat it.
    }

    @Test
    void initializeHandshakeExposesServerInfoAndSession() throws Exception {
        service = newService();
        service.start();

        HttpResponse<String> resp = post(initializeBody(), null);

        assertEquals(200, resp.statusCode());
        assertTrue(resp.headers().firstValue("Mcp-Session-Id").isPresent(), "a session id must be issued");
        assertTrue(resp.body().contains("mcp-test-server"), "the handshake should return serverInfo: " + resp.body());
    }

    @Test
    void registeredToolIsListedAndCallable() throws Exception {
        service = newService();
        service.start();
        service.registerTool("echo_back", "回显输入文本，测试工具",
                "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}",
                (exchange, request) -> CallToolResultOk.of("回声: " + request.arguments().get("text")));

        HttpResponse<String> init = post(initializeBody(), null);
        String session = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
        assertEquals(202, post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", session).statusCode());

        JsonNode list = readSseData(post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", session));
        JsonNode tools = list.path("result").path("tools");
        assertEquals("echo_back", tools.get(0).path("name").asText());

        JsonNode call = readSseData(post(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"echo_back\",\"arguments\":{\"text\":\"链路打通\"}}}",
                session));
        assertEquals("回声: 链路打通",
                call.path("result").path("content").get(0).path("text").asText());
        assertFalse(call.path("result").path("isError").asBoolean(true));
    }

    @Test
    void rootsListChangedNotificationIsShortCircuited() throws Exception {
        service = newService();
        service.start();
        // With the roots capability declared, the SDK answers list_changed with roots/list and waits 10 s.
        HttpResponse<String> init = post(initializeBodyWithRoots(), null);
        String session = init.headers().firstValue("Mcp-Session-Id").orElseThrow();

        long start = System.nanoTime();
        HttpResponse<String> resp = post(
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/roots/list_changed\"}", session);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(202, resp.statusCode(), "roots/list_changed must get 202 at once: " + resp.body());
        assertTrue(elapsedMs < 3000, "the SDK's roots/list request must be short-circuited; took " + elapsedMs + "ms");
        // The short circuit doesn't affect later requests.
        JsonNode list = readSseData(post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", session));
        assertTrue(list.path("result").has("tools"), "tools/list should still work after the short circuit: " + list);
    }

    /** DNS rebinding: a browser reaches loopback with its page's domain as Host, which alone must get it rejected. */
    @Test
    void rejectsRequestAddressedToAnotherHost() throws Exception {
        service = newService();
        service.start();

        RawResponse resp = rawPost("evil.example:" + service.getBoundPort(), null,
                initializeBody().getBytes(StandardCharsets.UTF_8));

        assertEquals(403, resp.status());
        assertFalse(resp.hasHeader("Mcp-Session-Id"), "a rejected request must not create a session");
    }

    /** A same-origin GET for the SSE stream has no Origin; the front door checks every method, not only POST. */
    @Test
    void rejectsGetAddressedToAnotherHost() throws Exception {
        service = newService();
        service.start();

        RawResponse resp = raw(("GET /mcp HTTP/1.1\r\n"
                + "Host: evil.example:" + service.getBoundPort() + "\r\n"
                + "Accept: text/event-stream\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));

        assertEquals(403, resp.status());
    }

    /** Shared Host/Origin check: only loopback names on this port, not look-alike domains; no port means port 80. */
    @Test
    void loopbackAuthorityMatchesOnlyLoopbackNamesOnThisPort() {
        assertTrue(McpServerService.isLoopbackAuthority("127.0.0.1:5173", 5173));
        assertTrue(McpServerService.isLoopbackAuthority("LOCALHOST:5173", 5173));
        assertFalse(McpServerService.isLoopbackAuthority("127.0.0.1:5174", 5173));
        assertFalse(McpServerService.isLoopbackAuthority("127.0.0.1.nip.io:5173", 5173));
        assertFalse(McpServerService.isLoopbackAuthority("localhost.evil.example:5173", 5173));
        assertFalse(McpServerService.isLoopbackAuthority("127.0.0.1", 5173));
        assertTrue(McpServerService.isLoopbackAuthority("127.0.0.1", 80), "port 80 clients send Host without a port");
        assertTrue(McpServerService.isLoopbackAuthority("localhost", 80));
        assertFalse(McpServerService.isLoopbackAuthority("evil.example", 80));
        assertFalse(McpServerService.isLoopbackAuthority(null, 5173));
    }

    /** A page on another local port calls cross-origin: the Host is right but the Origin isn't this server. */
    @Test
    void rejectsRequestFromAnotherOrigin() throws Exception {
        service = newService();
        service.start();
        int port = service.getBoundPort();

        RawResponse resp = rawPost("127.0.0.1:" + port, "http://127.0.0.1:" + (port + 1),
                initializeBody().getBytes(StandardCharsets.UTF_8));

        assertEquals(403, resp.status());
        assertFalse(resp.hasHeader("Mcp-Session-Id"), "a rejected request must not create a session");
    }

    /** A client configured with localhost sends {@code Host: localhost:<port>}, which is loopback too. */
    @Test
    void acceptsLocalhostHostName() throws Exception {
        service = newService();
        service.start();

        RawResponse resp = rawPost("localhost:" + service.getBoundPort(), null,
                initializeBody().getBytes(StandardCharsets.UTF_8));

        assertEquals(200, resp.status());
        assertTrue(resp.hasHeader("Mcp-Session-Id"), "a session id must be issued");
    }

    /** An oversized declared length gets 413 at once; the body is never sent, so reading it first would hang. */
    @Test
    void rejectsDeclaredOversizedBodyWithoutReadingIt() throws Exception {
        service = newService();
        service.start();

        RawResponse resp = raw(("POST /mcp HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + service.getBoundPort() + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Accept: application/json, text/event-stream\r\n"
                + "Content-Length: " + (McpServerService.MAX_BODY_BYTES + 1) + "\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));

        assertEquals(413, resp.status());
    }

    /**
     * A chunked upload without a length, padded one byte past the limit, gets 413 once that much is read. The final
     * chunk is never sent, so a server that reads the whole body before checking would hang.
     */
    @Test
    void rejectsChunkedBodyOnceItPassesTheLimit() throws Exception {
        service = newService();
        service.start();
        byte[] body = padded(initializeBody(), McpServerService.MAX_BODY_BYTES + 1);

        ByteArrayOutputStream request = new ByteArrayOutputStream();
        request.write(("POST /mcp HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + service.getBoundPort() + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Accept: application/json, text/event-stream\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "Connection: close\r\n\r\n"
                + Integer.toHexString(body.length) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        request.write(body);
        RawResponse resp = raw(request.toByteArray());

        assertEquals(413, resp.status());
        assertFalse(resp.hasHeader("Mcp-Session-Id"), "an oversized request must not create a session");
    }

    /** Each request returns its permit, so more sequential requests than permits never block or get rejected. */
    @Test
    void postPermitsAreReturnedAfterEachRequest() throws Exception {
        service = newService();
        service.start();

        for (int i = 1; i <= McpServerService.MAX_CONCURRENT_POSTS + 1; i++) {
            assertEquals(200, post(initializeBody(), null).statusCode(), "request " + i + " should succeed");
        }
    }

    /**
     * A start that fails on an occupied port leaves no half-started state (registering a tool still fails) and can be
     * retried once the port is free.
     */
    @Test
    void failedStartLeavesNothingBehindAndCanBeRetried() throws Exception {
        service = newService();
        int port;
        try (ServerSocket occupied = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            port = occupied.getLocalPort();
            RuntimeParamRegistry.setStartupValue(FrameRuntimeParams.MCP_PORT, port, RuntimeParamSource.PROGRAMMATIC);

            assertThrows(IllegalStateException.class, service::start);
            assertThrows(IllegalStateException.class, () -> service.registerTool("echo_back", "回显",
                    "{\"type\":\"object\"}", (exchange, request) -> CallToolResultOk.of("x")),
                    "registering a tool must fail after a failed start");
            assertEquals(-1, service.getBoundPort());
        }

        service.start();

        assertEquals(port, service.getBoundPort());
        assertEquals(200, post(initializeBody(), null).statusCode());
    }

    /** A start arriving after dispose, as when quitting right after launch, must not serve or write a port file. */
    @Test
    void startAfterDisposeIsRefused() {
        service = newService();
        service.dispose();

        assertThrows(IllegalStateException.class, service::start);
        assertEquals(-1, service.getBoundPort());
        assertFalse(Files.exists(PathManager.getSystemDir().resolve("mcp/mcp-test-server.url")), "no port file left");
    }

    @Test
    void disposeClosesPort() throws Exception {
        service = newService();
        service.start();
        int port = service.getBoundPort();
        assertTrue(port > 0);

        service.dispose();
        assertEquals(-1, service.getBoundPort(), "the old port must not be reported after dispose");

        boolean refused = false;
        try {
            HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/mcp"))
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (java.io.IOException e) {
            refused = true;
        }
        assertTrue(refused, "the port should refuse connections after dispose");
        service = null; // Already disposed; tearDown must not repeat it.
    }

    private McpServerService newService() {
        return new McpServerService("mcp-test-server", "0.0.1", MAPPER);
    }

    private static String initializeBody() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                + "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"mcp-test\",\"version\":\"0\"}}}";
    }

    /** Declares the roots capability, as goose does; the SDK sends roots/list only to clients that declare it. */
    private static String initializeBodyWithRoots() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                + "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"roots\":{}},"
                + "\"clientInfo\":{\"name\":\"mcp-test\",\"version\":\"0\"}}}";
    }

    private HttpResponse<String> post(String body, String sessionId) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(service.getUrl()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (sessionId != null) {
            builder.header("Mcp-Session-Id", sessionId)
                    .header("MCP-Protocol-Version", "2025-06-18");
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private RawResponse rawPost(String host, String origin, byte[] body) throws Exception {
        StringBuilder head = new StringBuilder("POST /mcp HTTP/1.1\r\n")
                .append("Host: ").append(host).append("\r\n");
        if (origin != null) {
            head.append("Origin: ").append(origin).append("\r\n");
        }
        head.append("Content-Type: application/json\r\n")
                .append("Accept: application/json, text/event-stream\r\n")
                .append("Content-Length: ").append(body.length).append("\r\n")
                .append("Connection: close\r\n\r\n");
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        request.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        request.write(body);
        return raw(request.toByteArray());
    }

    /**
     * Sends a raw socket request, since HttpClient won't set Host or Content-Length, and reads the status line and
     * header names; the status is -1 when no response arrives.
     */
    private RawResponse raw(byte[] request) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", service.getBoundPort())) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(request);
            socket.getOutputStream().flush();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            String statusLine;
            try {
                statusLine = in.readLine();
            } catch (SocketTimeoutException noResponse) {
                return new RawResponse(-1, List.of());
            }
            if (statusLine == null) {
                return new RawResponse(-1, List.of());
            }
            List<String> headerNames = new ArrayList<>();
            for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headerNames.add(line.substring(0, colon).trim().toLowerCase(Locale.ROOT));
                }
            }
            return new RawResponse(Integer.parseInt(statusLine.split(" ")[1]), headerNames);
        }
    }

    private record RawResponse(int status, List<String> headerNames) {
        boolean hasHeader(String name) {
            return headerNames.contains(name.toLowerCase(Locale.ROOT));
        }
    }

    /** Pads the JSON text with trailing spaces to exactly size bytes; the JSON stays valid. */
    private static byte[] padded(String json, int size) {
        byte[] body = new byte[size];
        Arrays.fill(body, (byte) ' ');
        byte[] text = json.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(text, 0, body, 0, text.length);
        return body;
    }

    /** Returns the JSON payload of a response that may be an SSE frame (event: message\\ndata: {...}). */
    private static JsonNode readSseData(HttpResponse<String> resp) throws Exception {
        assertEquals(200, resp.statusCode(), "request failed: " + resp.body());
        for (String line : resp.body().split("\n")) {
            if (line.startsWith("data:")) {
                return MAPPER.readTree(line.substring(5).trim());
            }
        }
        // Without SSE the whole body is JSON.
        return MAPPER.readTree(resp.body());
    }

    /** Builds a successful text tool result. */
    private static final class CallToolResultOk {
        static io.modelcontextprotocol.spec.McpSchema.CallToolResult of(String text) {
            return io.modelcontextprotocol.spec.McpSchema.CallToolResult.builder()
                    .addTextContent(text).isError(false).build();
        }
    }
}
