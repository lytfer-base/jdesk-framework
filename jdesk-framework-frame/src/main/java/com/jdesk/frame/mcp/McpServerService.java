package com.jdesk.frame.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdesk.core.app.PathManager;
import com.jdesk.core.disposer.Disposable;
import com.jdesk.core.runtime.RuntimeParamRegistry;
import com.jdesk.frame.runtime.params.FrameRuntimeParams;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee11.servlet.FilterHolder;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/**
 * Embedded MCP server over loopback-only Streamable HTTP. It provides the generic mechanics; applications register
 * their tools through {@link #registerTool}.
 * The registration API exposes MCP SDK types; if you replace the SDK, change the API with it.
 */
public final class McpServerService implements Disposable {

    private static final Logger LOG = LoggerFactory.getLogger(McpServerService.class);
    private static final String MCP_ENDPOINT = "/mcp";
    /** Body limit in bytes: checked against Content-Length, or by reading one byte past it for chunked uploads. */
    static final int MAX_BODY_BYTES = 1024 * 1024;
    /** Maximum POSTs in flight; others wait up to {@link #PERMIT_WAIT_SECONDS} seconds, then get 503. */
    static final int MAX_CONCURRENT_POSTS = 10;
    private static final long PERMIT_WAIT_SECONDS = 30;
    private static final int WARN_REJECTIONS = 5;
    private static final long CLOSE_TIMEOUT_MS = 2_000;

    private final String serverName;
    private final String serverVersion;
    private final ObjectMapper objectMapper;
    private final JacksonMcpJsonMapper jsonMapper;

    // jetty, portFile and disposed are accessed only in start and dispose, under the lock.
    private Server jetty;
    private Path portFile;
    private boolean disposed;
    private volatile McpSyncServer mcpServer;
    private volatile int boundPort = -1;

    public McpServerService(String serverName, String serverVersion, ObjectMapper objectMapper) {
        this.serverName = serverName;
        this.serverVersion = serverVersion;
        this.objectMapper = objectMapper;
        this.jsonMapper = new JacksonMcpJsonMapper(objectMapper);
    }

    /**
     * Starts the loopback HTTP server on the frame.mcp.port runtime parameter, or a random port by default. A failed
     * start leaves no state behind and can be retried.
     *
     * @throws IllegalStateException if already started or disposed
     */
    public synchronized void start() {
        if (disposed) {
            throw new IllegalStateException("McpServerService disposed");
        }
        if (jetty != null) {
            throw new IllegalStateException("McpServerService already started");
        }
        int port = RuntimeParamRegistry.contains(FrameRuntimeParams.MCP_PORT_KEY)
                ? RuntimeParamRegistry.getInt(FrameRuntimeParams.MCP_PORT_KEY) : 0;
        HttpServletStreamableServerTransportProvider provider = HttpServletStreamableServerTransportProvider
                .builder().mcpEndpoint(MCP_ENDPOINT).jsonMapper(jsonMapper).build();
        McpSyncServer server = McpServer.sync(provider)
                .serverInfo(serverName, serverVersion)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .build();
        Server http = new Server(new InetSocketAddress("127.0.0.1", port));
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addFilter(new FilterHolder(new EndpointGuard()),
                MCP_ENDPOINT + "/*", EnumSet.of(jakarta.servlet.DispatcherType.REQUEST));
        context.addServlet(new ServletHolder(provider), MCP_ENDPOINT + "/*");
        http.setHandler(context);
        try {
            http.start();
        } catch (Exception e) {
            stopQuietly(http);
            throw new IllegalStateException("MCP server failed to start", e);
        }
        jetty = http;
        mcpServer = server;
        boundPort = ((ServerConnector) http.getConnectors()[0]).getLocalPort();
        writePortFile();
        LOG.info("MCP server '{}' listening on http://127.0.0.1:{}{}", serverName, boundPort, MCP_ENDPOINT);
    }

    /** Writes {@code <system>/mcp/<serverName>.url}, holding the full endpoint URL, so the stdio bridge can find it. */
    private void writePortFile() {
        try {
            Path dir = PathManager.getSystemDir().resolve("mcp");
            Files.createDirectories(dir);
            portFile = dir.resolve(serverName + ".url");
            Files.writeString(portFile, getUrl(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Only discovery fails; the server still works at its URL.
            LOG.warn("MCP port file write failed", e);
        }
    }

    /**
     * Registers a tool; {@code inputSchemaJson} is JSON Schema text. The handler runs synchronously on a server thread,
     * never the EDT; do UI work through the EDT yourself.
     *
     * @throws IllegalStateException if the server isn't started
     */
    public void registerTool(String name, String description, String inputSchemaJson,
            BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> handler) {
        if (mcpServer == null) {
            throw new IllegalStateException("McpServerService not started");
        }
        Tool tool = Tool.builder(name).description(description)
                .inputSchema(jsonMapper, inputSchemaJson).build();
        mcpServer.addTool(new SyncToolSpecification(tool, handler));
    }

    public void removeTool(String name) {
        if (mcpServer != null) {
            mcpServer.removeTool(name);
        }
    }

    /** Returns the bound port, or -1 when not started. */
    public int getBoundPort() {
        return boundPort;
    }

    public String getUrl() {
        return "http://127.0.0.1:" + boundPort + MCP_ENDPOINT;
    }

    /** Closes sessions, stops Jetty and deletes the port file; repeatable, and {@link #start()} fails afterwards. */
    @Override
    public synchronized void dispose() {
        disposed = true;
        McpSyncServer server = mcpServer;
        mcpServer = null;
        boundPort = -1;
        if (server != null) {
            closeSessions(server);
        }
        if (jetty != null) {
            stopQuietly(jetty);
            jetty = null;
        }
        if (portFile != null) {
            try {
                Files.deleteIfExists(portFile);
            } catch (Exception e) {
                LOG.warn("MCP port file delete failed", e);
            }
            portFile = null;
        }
        LOG.info("MCP server '{}' stopped", serverName);
    }

    /**
     * Closes sessions gracefully but waits only a bounded time, since the SDK call has no timeout; stopping Jetty
     * afterwards interrupts stuck writes.
     */
    private static void closeSessions(McpSyncServer server) {
        Thread closer = new Thread(() -> {
            try {
                server.closeGracefully();
            } catch (RuntimeException e) {
                LOG.warn("MCP server graceful close failed", e);
            }
        }, "mcp-close");
        closer.setDaemon(true);
        closer.start();
        try {
            closer.join(CLOSE_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (closer.isAlive()) {
            LOG.warn("MCP server graceful close timed out after {} ms", CLOSE_TIMEOUT_MS);
        }
    }

    private static void stopQuietly(Server http) {
        try {
            http.stop();
        } catch (Exception e) {
            LOG.warn("MCP server jetty stop failed", e);
        }
    }

    /**
     * Returns whether a Host value (or an Origin without {@code http://}) is 127.0.0.1 or localhost with the port the
     * request arrived on; a missing port counts as 80.
     */
    static boolean isLoopbackAuthority(String authority, int port) {
        if (authority == null) {
            return false;
        }
        String value = authority.trim().toLowerCase(Locale.ROOT);
        return value.equals("127.0.0.1:" + port) || value.equals("localhost:" + port)
                || port == 80 && (value.equals("127.0.0.1") || value.equals("localhost"));
    }

    /**
     * Front door of /mcp for every method: 403 for a non-loopback Host or a foreign Origin, 413 for an oversized
     * body, a queue for POSTs over the in-flight limit, and 202 for a POST holding just a roots/list_changed notice.
     * The SDK's securityValidator isn't set, so Host and Origin must be checked here before the body is read; a
     * permit is held until the SDK returns, after the tool finishes.
     * Don't remove the roots shortcut: the SDK would query roots/list for that notice and wait the full 10 seconds.
     */
    private final class EndpointGuard implements Filter {
        private final AtomicInteger rejections = new AtomicInteger();
        private final Semaphore postPermits = new Semaphore(MAX_CONCURRENT_POSTS);

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            HttpServletRequest http = (HttpServletRequest) request;
            HttpServletResponse resp = (HttpServletResponse) response;
            String host = http.getHeader("Host");
            if (!isLoopbackAuthority(host, http.getLocalPort())) {
                reject(resp, 403, "host", host);
                return;
            }
            String origin = http.getHeader("Origin");
            if (origin != null && !isOwnOrigin(origin, http.getLocalPort())) {
                reject(resp, 403, "origin", origin);
                return;
            }
            if (!"POST".equals(http.getMethod())) {
                chain.doFilter(request, response);
                return;
            }
            long declared = http.getContentLengthLong();
            if (declared > MAX_BODY_BYTES) {
                reject(resp, 413, "body", declared + " bytes");
                return;
            }
            if (!acquirePostPermit()) {
                reject(resp, 503, "busy", MAX_CONCURRENT_POSTS + " in flight");
                return;
            }
            try {
                byte[] body = http.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
                if (body.length > MAX_BODY_BYTES) {
                    reject(resp, 413, "body", "chunked");
                    return;
                }
                if (isRootsListChangedOnly(body)) {
                    resp.setStatus(HttpServletResponse.SC_ACCEPTED);
                    return;
                }
                chain.doFilter(new CachedBodyRequest(http, body), response);
            } finally {
                postPermits.release();
            }
        }

        private boolean acquirePostPermit() {
            try {
                return postPermits.tryAcquire(PERMIT_WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private void reject(HttpServletResponse resp, int status, String reason, String detail) {
            if (rejections.incrementAndGet() <= WARN_REJECTIONS) {
                LOG.warn("mcp.request.rejected status={} reason={} detail={}", status, reason, detail);
            } else {
                LOG.debug("mcp.request.rejected status={} reason={} detail={}", status, reason, detail);
            }
            resp.setStatus(status);
            resp.setContentLength(0);
        }

        private static boolean isOwnOrigin(String origin, int port) {
            String value = origin.trim().toLowerCase(Locale.ROOT);
            return value.startsWith("http://") && isLoopbackAuthority(value.substring("http://".length()), port);
        }

        private boolean isRootsListChangedOnly(byte[] body) {
            try {
                JsonNode node = objectMapper.readTree(body);
                return node.isObject() && !node.has("id")
                        && "notifications/roots/list_changed".equals(node.path("method").asText());
            } catch (Exception unparsable) {
                return false;
            }
        }
    }

    /** Replays the already-read body to the downstream servlet. */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public jakarta.servlet.ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new jakarta.servlet.ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(jakarta.servlet.ReadListener listener) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
