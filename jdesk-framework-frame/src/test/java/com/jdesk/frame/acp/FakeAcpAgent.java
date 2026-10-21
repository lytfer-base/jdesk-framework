package com.jdesk.frame.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Fake ACP agent for protocol tests: reads JSONL requests from stdin and answers from a fixed script. argv[0] is a log
 * file that receives each raw client reply to an agent request (permission or unhandled method) for the tests to
 * check. Exits on stdin EOF.
 */
public final class FakeAcpAgent {

    private FakeAcpAgent() {
    }

    public static void main(String[] args) throws Exception {
        Path logFile = Path.of(args[0]);
        ObjectMapper mapper = new ObjectMapper();
        PrintWriter out = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

        String line;
        while ((line = in.readLine()) != null) {
            JsonNode msg = mapper.readTree(line);
            String method = msg.path("method").asText(null);
            if (method == null) {
                continue; // Replies to our agent requests are read by the prompt branch and don't normally get here.
            }
            JsonNode id = msg.get("id");
            switch (method) {
                case "initialize" -> reply(out, id, mapper.createObjectNode()
                        .put("protocolVersion", 1)
                        .set("agentInfo", mapper.createObjectNode()
                                .put("name", "fake-acp").put("version", "0.1.0")));
                case "session/new" -> {
                    if (!msg.path("params").has("cwd")) {
                        replyError(out, id, -32603, "cwd required");
                    } else {
                        reply(out, id, mapper.createObjectNode()
                                .put("sessionId", "sess-1")
                                .set("modes", mapper.createObjectNode()
                                        .put("currentModeId", "auto")
                                        .set("availableModes", mapper.createArrayNode()
                                                .add(mapper.createObjectNode().put("id", "auto"))
                                                .add(mapper.createObjectNode().put("id", "approve")))));
                    }
                }
                case "session/set_mode" -> reply(out, id, mapper.createObjectNode());
                case "session/load" -> {
                    // Replay script: two history messages, then a result with modes, shaped like goose 1.47's.
                    String loadId = msg.path("params").path("sessionId").asText("sess-1");
                    notify(out, mapper, loadId, "user_message_chunk", "历史提问");
                    notify(out, mapper, loadId, "agent_message_chunk", "历史回答");
                    reply(out, id, mapper.createObjectNode()
                            .set("modes", mapper.createObjectNode()
                                    .put("currentModeId", "auto")
                                    .set("availableModes", mapper.createArrayNode()
                                            .add(mapper.createObjectNode().put("id", "auto")))));
                }
                case "session/list" -> {
                    ObjectNode entry = mapper.createObjectNode()
                            .put("sessionId", "sess-1")
                            .put("cwd", msg.path("params").path("cwd").asText(""))
                            .put("title", "假历史会话");
                    reply(out, id, mapper.createObjectNode()
                            .set("sessions", mapper.createArrayNode().add(entry)));
                }
                case "session/delete" -> reply(out, id, mapper.createObjectNode());
                case "test/hang" -> {
                    // Never answers, so a request stays in flight for the process-exit test.
                }
                case "session/prompt" -> handlePrompt(mapper, in, out, logFile, msg, id);
                default -> replyError(out, id, -32601, "unknown method " + method);
            }
        }
    }

    /** Prompt script: streams updates, sends a keyword-triggered agent request, then replies with the stop reason. */
    private static void handlePrompt(ObjectMapper mapper, BufferedReader in, PrintWriter out,
                                     Path logFile, JsonNode msg, JsonNode id) throws Exception {
        JsonNode params = msg.path("params");
        String sessionId = params.path("sessionId").asText("sess-1");
        String text = params.path("prompt").path(0).path("text").asText("");

        notify(out, mapper, sessionId, "agent_thought_chunk", "思考中");
        notify(out, mapper, sessionId, "agent_message_chunk", "你好");
        notify(out, mapper, sessionId, "agent_message_chunk", "世界");

        if (text.contains("慢")) {
            // Cancel script: hold the turn until session/cancel arrives, log it raw and reply cancelled.
            String line;
            while ((line = in.readLine()) != null) {
                JsonNode incoming = mapper.readTree(line);
                if ("session/cancel".equals(incoming.path("method").asText(null))) {
                    appendLog(logFile, line);
                    reply(out, id, mapper.createObjectNode().put("stopReason", "cancelled"));
                    return;
                }
            }
            reply(out, id, mapper.createObjectNode().put("stopReason", "end_turn"));
            return;
        }

        ObjectNode result = mapper.createObjectNode().put("stopReason", "end_turn");
        if (text.contains("写")) {
            ObjectNode permissionParams = mapper.createObjectNode().put("sessionId", sessionId);
            permissionParams.set("toolCall", mapper.createObjectNode().put("title", "写文件"));
            var options = permissionParams.putArray("options");
            for (String kind : new String[] {"allow_once", "allow_always", "reject_once", "reject_always"}) {
                options.add(mapper.createObjectNode().put("optionId", kind).put("kind", kind));
            }
            send(out, mapper, 9001, "session/request_permission", permissionParams);
            String permissionReply = in.readLine(); // Waits for the client's reply.
            appendLog(logFile, permissionReply);
            result.put("permissionReply", permissionReply);
        } else if (text.contains("读文件")) {
            send(out, mapper, 9002, "fs/read_text_file",
                    mapper.createObjectNode().put("sessionId", sessionId).put("path", "D:/tmp/x.txt"));
            appendLog(logFile, in.readLine());
        }
        reply(out, id, result);
    }

    private static void notify(PrintWriter out, ObjectMapper mapper,
                               String sessionId, String kind, String text) {
        ObjectNode msg = mapper.createObjectNode().put("jsonrpc", "2.0").put("method", "session/update");
        ObjectNode params = msg.putObject("params").put("sessionId", sessionId);
        params.putObject("update").put("sessionUpdate", kind)
                .set("content", mapper.createObjectNode().put("type", "text").put("text", text));
        out.println(msg.toString());
    }

    private static void send(PrintWriter out, ObjectMapper mapper, long id, String method, JsonNode params) {
        ObjectNode msg = mapper.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", method);
        msg.set("params", params);
        out.println(msg.toString());
    }

    private static void reply(PrintWriter out, JsonNode id, JsonNode result) {
        ObjectNode msg = new ObjectMapper().createObjectNode().put("jsonrpc", "2.0");
        msg.set("id", id);
        msg.set("result", result);
        out.println(msg.toString());
    }

    private static void replyError(PrintWriter out, JsonNode id, int code, String message) {
        ObjectNode msg = new ObjectMapper().createObjectNode().put("jsonrpc", "2.0");
        msg.set("id", id);
        msg.set("error", new ObjectMapper().createObjectNode().put("code", code).put("message", message));
        out.println(msg.toString());
    }

    private static void appendLog(Path logFile, String line) throws Exception {
        Files.writeString(logFile, line + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
