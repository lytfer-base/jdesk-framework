package com.jdesk.frame.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Writer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * ACP client: JSON-RPC 2.0 over stdio, one frame per line (JSONL), relying only on the ACP spec so it can drive any
 * ACP agent. A frame with a method and an id is an agent request that must be answered, one with only a method is a
 * notification, and one with only an id is the response to our request with that id.
 * Don't treat a received answer as success: some agents report model or provider failures as ordinary assistant text.
 */
public final class AcpClient {

    private static final Logger LOG = LoggerFactory.getLogger(AcpClient.class);

    /** Handles an agent request: the return value goes back as the result, and a throw as a JSON-RPC error. */
    @FunctionalInterface
    public interface AgentRequestHandler {
        JsonNode handle(String method, JsonNode params) throws Exception;
    }

    private final AcpAgentProcess process;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong nextId = new AtomicLong(0);
    private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    /** Guards {@code processExited} and changes to {@code pending}, so process exit and new requests don't race. */
    private final Object pendingLock = new Object();
    private boolean processExited;

    private volatile BiConsumer<String, JsonNode> notificationListener = (method, params) -> { };
    /** Rejects every permission request by default. */
    private volatile Function<AcpPermissionRequest, String> permissionHandler =
            request -> null;
    private volatile AgentRequestHandler agentRequestHandler = (method, params) -> {
        throw new AcpMethodNotFoundException(method);
    };

    public AcpClient(AcpAgentProcess process) {
        this.process = process;
    }

    public void attach() {
        process.addFrameListener(this::dispatch);
        process.addExitListener(this::failAllPending);
    }

    /**
     * Fails in-flight requests as soon as the process exits, so callers don't wait out the timeout.
     * Must share the lock with registration in {@link #request}, so that a later request fails at once.
     */
    private void failAllPending(int exitCode) {
        synchronized (pendingLock) {
            processExited = true;
            pending.values().forEach(future -> future.completeExceptionally(
                    new IllegalStateException("agent process exited, code=" + exitCode)));
            pending.clear();
        }
    }

    /** Sets the listener for notifications such as session/update; it runs on the stdout reader thread, not the EDT. */
    public void setNotificationListener(BiConsumer<String, JsonNode> listener) {
        this.notificationListener = listener == null ? (method, params) -> { } : listener;
    }

    /**
     * Sets the {@code session/request_permission} handler, which returns the chosen optionId or null to cancel.
     * Without one, every request is answered as cancelled.
     */
    public void setPermissionHandler(Function<AcpPermissionRequest, String> handler) {
        this.permissionHandler = handler;
    }

    /**
     * Sends a request and waits for its result; don't call it on the EDT.
     *
     * @throws AcpRequestException on a protocol error, a timeout, an interrupt or an exited process
     */
    public JsonNode request(String method, ObjectNode params, long timeoutSec) {
        long id = nextId.getAndIncrement();
        ObjectNode msg = mapper.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("id", id);
        msg.put("method", method);
        msg.set("params", params);
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        synchronized (pendingLock) {
            if (processExited) {
                throw new AcpRequestException(method, -1, "agent process already exited", null);
            }
            pending.put(id, future);
        }
        send(msg);
        JsonNode result;
        try {
            result = future.get(timeoutSec, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pending.remove(id);
            throw new AcpRequestException(method, -1, "interrupted", null);
        } catch (TimeoutException e) {
            pending.remove(id);
            throw new AcpRequestException(method, -1, "timeout after " + timeoutSec + "s", null);
        } catch (ExecutionException e) {
            pending.remove(id);
            throw new AcpRequestException(method, -1, String.valueOf(e.getCause()), null);
        }
        JsonNode error = result.get("error");
        if (error != null) {
            throw new AcpRequestException(method,
                    error.path("code").asInt(-1),
                    error.path("message").asText("unknown"),
                    error.hasNonNull("data") ? error.get("data").asText() : null);
        }
        return result.has("result") ? result.get("result") : mapper.nullNode();
    }

    // ---- Typed wrappers for standard ACP methods ----

    /** Performs the ACP handshake, declaring the client's capabilities and identity. */
    public JsonNode initialize(String clientName, String clientVersion) {
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", 1);
        ObjectNode capabilities = params.putObject("clientCapabilities");
        capabilities.putObject("fs").put("readTextFile", false).put("writeTextFile", false);
        params.putObject("clientInfo").put("name", clientName).put("version", clientVersion);
        return request("initialize", params, 30);
    }

    /** Creates a session with {@code cwd} in forward slashes; an empty or null {@code mcpServers} attaches none. */
    public JsonNode newSession(String cwd, ArrayNode mcpServers) {
        ObjectNode params = mapper.createObjectNode();
        params.put("cwd", cwd.replace('\\', '/'));
        params.set("mcpServers", mcpServers == null ? mapper.createArrayNode() : mcpServers);
        return request("session/new", params, 60);
    }

    /** Sends a text prompt and waits for the turn to end (stopReason); streamed updates arrive as notifications. */
    public JsonNode prompt(String sessionId, String text, long timeoutSec) {
        ArrayNode prompt = mapper.createArrayNode();
        prompt.addObject().put("type", "text").put("text", text);
        return prompt(sessionId, prompt, timeoutSec);
    }

    /** Sends a prompt made of ACP content blocks: text, or image with base64 data and a mimeType. */
    public JsonNode prompt(String sessionId, ArrayNode promptBlocks, long timeoutSec) {
        ObjectNode params = mapper.createObjectNode();
        params.put("sessionId", sessionId);
        params.set("prompt", promptBlocks == null ? mapper.createArrayNode() : promptBlocks);
        return request("session/prompt", params, timeoutSec);
    }

    /** Loads an existing session, replaying its history as session/update notifications; the result carries modes. */
    public JsonNode loadSession(String sessionId, String cwd, ArrayNode mcpServers) {
        ObjectNode params = mapper.createObjectNode();
        params.put("sessionId", sessionId);
        params.put("cwd", cwd.replace('\\', '/'));
        params.set("mcpServers", mcpServers == null ? mapper.createArrayNode() : mcpServers);
        return request("session/load", params, 60);
    }

    /** Lists sessions; needs an agent that advertises {@code sessionCapabilities.list}. */
    public JsonNode listSessions(String cwd) {
        ObjectNode params = mapper.createObjectNode();
        params.put("cwd", cwd.replace('\\', '/'));
        return request("session/list", params, 30);
    }

    /** Deletes a session; needs an agent that advertises {@code sessionCapabilities.delete}. */
    public void deleteSession(String sessionId) {
        ObjectNode params = mapper.createObjectNode();
        params.put("sessionId", sessionId);
        request("session/delete", params, 30);
    }

    /** Cancels the current turn by notification; the pending session/prompt should end with stopReason=cancelled. */
    public void cancelSession(String sessionId) {
        ObjectNode params = mapper.createObjectNode();
        params.put("sessionId", sessionId);
        notify("session/cancel", params);
    }

    /** Switches the session mode. Keep the snake_case method name; FakeAcpAgent answers only that form. */
    public void setMode(String sessionId, String modeId) {
        ObjectNode params = mapper.createObjectNode();
        params.put("sessionId", sessionId);
        params.put("modeId", modeId);
        request("session/set_mode", params, 15);
    }

    /**
     * Builds an mcpServers entry for a Streamable HTTP MCP server.
     * Keep the empty headers array: some agents silently drop an entry without it.
     */
    public ObjectNode httpMcpServerEntry(String name, String url) {
        ObjectNode entry = mapper.createObjectNode();
        entry.put("type", "http");
        entry.put("name", name);
        entry.put("url", url);
        entry.putArray("headers");
        return entry;
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    // ---- Sending and dispatching frames ----

    /** Sends a JSON-RPC notification (no id, no answer); throws {@link AcpRequestException} if the write fails. */
    public void notify(String method, ObjectNode params) {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("method", method);
        msg.set("params", params);
        send(msg);
    }

    private void send(ObjectNode msg) {
        try {
            String line = mapper.writeValueAsString(msg);
            LOG.debug("acp >>> method={} id={} chars={}",
                    msg.path("method").asText("-"), msg.path("id").asText("-"), line.length());
            Writer stdin = process.stdin();
            synchronized (stdin) {
                stdin.write(line);
                stdin.write("\n");
                stdin.flush();
            }
        } catch (Exception e) {
            throw new AcpRequestException(msg.path("method").asText("?"), -1,
                    "write to agent stdin failed: " + e.getMessage(), null);
        }
    }

    private void dispatch(String line) {
        JsonNode msg;
        try {
            msg = mapper.readTree(line);
        } catch (Exception e) {
            LOG.warn("acp non-JSON frame ignored chars={}", line.length());
            return;
        }
        JsonNode methodNode = msg.get("method");
        JsonNode id = msg.get("id");
        if (methodNode != null && id != null) {
            handleAgentRequest(msg, methodNode.asText(), id);
        } else if (methodNode != null) {
            LOG.debug("acp <<< notify {}", methodNode.asText());
            notificationListener.accept(methodNode.asText(),
                    msg.has("params") ? msg.get("params") : mapper.nullNode());
        } else if (id != null) {
            CompletableFuture<JsonNode> future = pending.remove(id.asLong());
            if (future == null) {
                LOG.warn("acp response for unknown id={}", id);
            } else {
                future.complete(msg);
            }
        } else {
            LOG.warn("acp frame with neither method nor id type={} chars={}", msg.getNodeType(), line.length());
        }
    }

    private void handleAgentRequest(JsonNode msg, String method, JsonNode id) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        try {
            JsonNode result;
            if ("session/request_permission".equals(method)) {
                String optionId = permissionHandler.apply(AcpPermissionRequest.from(
                        msg.has("params") ? msg.get("params") : mapper.nullNode()));
                result = AcpPermissionRequest.outcomeSelected(optionId, mapper);
            } else {
                result = agentRequestHandler.handle(method,
                        msg.has("params") ? msg.get("params") : mapper.nullNode());
            }
            response.set("result", result);
        } catch (AcpMethodNotFoundException notFound) {
            LOG.info("acp agent request not handled, replying -32601: {}", method);
            response.set("error", mapper.createObjectNode()
                    .put("code", -32601).put("message", "Method not found: " + method));
        } catch (Exception failure) {
            LOG.warn("acp agent request handler failed: {}", method, failure);
            response.set("error", mapper.createObjectNode()
                    .put("code", -32603).put("message",
                            failure.getMessage() == null ? "internal error" : failure.getMessage()));
        }
        send(response);
    }

    /** Thrown when the agent rejects a request with a protocol error, or when the transport fails. */
    public static final class AcpRequestException extends RuntimeException {
        private final int code;
        private final String data;

        AcpRequestException(String method, int code, String message, String data) {
            super(method + " failed: [" + code + "] " + message + (data != null ? " data=" + data : ""));
            this.code = code;
            this.data = data;
        }

        public int code() {
            return code;
        }

        public String data() {
            return data;
        }
    }

    /** Thrown for an agent request method that the client doesn't handle. */
    public static final class AcpMethodNotFoundException extends Exception {
        public AcpMethodNotFoundException(String method) {
            super("no handler for " + method);
        }
    }
}
