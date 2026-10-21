package com.jdesk.frame.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Bridge from stdio to Streamable HTTP, letting stdio-only MCP clients such as Claude Desktop reach the embedded
 * server: {@code java -cp <app.jar> com.jdesk.frame.mcp.McpStdioBridge <target>}, where the target is a URL, a port
 * or the port file the server writes ({@code <system>/mcp/<name>.url}). Server push (the GET stream) isn't supported.
 * Write only single-line JSON-RPC messages to stdout; all diagnostics go to stderr.
 */
public final class McpStdioBridge {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROTOCOL_VERSION = "2025-06-18";

    private final String url;
    private String sessionId;

    private McpStdioBridge(String url) {
        this.url = url;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: McpStdioBridge <url | port | portFile>");
            System.exit(2);
        }
        new McpStdioBridge(resolveTarget(args[0])).run();
    }

    /** Resolves the target: a URL as is, an existing file by its content (the URL), otherwise a port on loopback. */
    static String resolveTarget(String arg) throws IOException {
        if (arg.startsWith("http://") || arg.startsWith("https://")) {
            return arg;
        }
        Path maybeFile = Path.of(arg);
        if (Files.isRegularFile(maybeFile)) {
            return Files.readString(maybeFile, StandardCharsets.UTF_8).trim();
        }
        return "http://127.0.0.1:" + Integer.parseInt(arg) + "/mcp";
    }

    private void run() throws IOException {
        HttpClient client = HttpClient.newHttpClient();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        BufferedWriter out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            try {
                forward(client, out, line);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception failure) {
                // One failed message must not kill the bridge: answer with a protocol error so the client doesn't wait.
                System.err.println("mcp.bridge.forward.failed: " + failure);
                writeError(out, line, failure);
            }
        }
    }

    private void forward(HttpClient client, BufferedWriter out, String line) throws Exception {
        JsonNode message = MAPPER.readTree(line);
        boolean notification = !message.has("id");
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", PROTOCOL_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(line, StandardCharsets.UTF_8));
        if (sessionId != null) {
            request.header("MCP-Session-Id", sessionId);
        }
        HttpResponse<String> response = client.send(request.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        response.headers().firstValue("MCP-Session-Id").ifPresent(value -> sessionId = value);
        if (notification || response.statusCode() == 202) {
            return;
        }
        if (response.statusCode() >= 400) {
            writeError(out, line, new IOException("HTTP " + response.statusCode() + ": " + response.body()));
            return;
        }
        String payload = isSse(response) ? extractSseData(response.body()) : response.body();
        if (payload == null || payload.isBlank()) {
            return;
        }
        // Reserialize to a single line: each JSON-RPC message on stdout must be exactly one line.
        out.write(MAPPER.readTree(payload).toString());
        out.newLine();
        out.flush();
    }

    private static boolean isSse(HttpResponse<?> response) {
        return response.headers().firstValue("Content-Type")
                .map(type -> type.startsWith("text/event-stream"))
                .orElse(false);
    }

    /** Returns the payload of the first SSE data frame, or null if there is none. */
    private static String extractSseData(String body) {
        for (String line : body.split("\n")) {
            if (line.startsWith("data:")) {
                return line.substring("data:".length()).trim();
            }
        }
        return null;
    }

    private static void writeError(BufferedWriter out, String requestLine, Exception failure) {
        try {
            JsonNode message = MAPPER.readTree(requestLine);
            if (!message.has("id")) {
                return;
            }
            ObjectNode error = MAPPER.createObjectNode();
            error.put("jsonrpc", "2.0");
            error.set("id", message.get("id"));
            ObjectNode body = error.putObject("error");
            body.put("code", -32000);
            body.put("message", String.valueOf(failure.getMessage()));
            out.write(error.toString());
            out.newLine();
            out.flush();
        } catch (Exception suppressed) {
            System.err.println("mcp.bridge.writeError.failed: " + suppressed);
        }
    }
}
