package com.jdesk.frame.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdesk.core.app.PathManager;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test for McpStdioBridge: a real server and a real bridge child process run a full MCP session over stdio.
 * The bridge finds its target through the port file, as it does when an MCP client launches it.
 */
class McpStdioBridgeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path homeDir;

    private McpServerService service;
    private Process bridge;

    @BeforeEach
    void setUp() {
        PathManager.setHomePathForTests(homeDir);
    }

    @AfterEach
    void tearDown() {
        if (bridge != null) {
            bridge.destroyForcibly();
        }
        if (service != null) {
            service.dispose();
        }
        PathManager.resetForTests();
    }

    @Test
    void bridgeRelaysInitializeListAndCallOverStdio() {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            service = new McpServerService("mcp-bridge-test", "0.0.1", MAPPER);
            service.start();
            service.registerTool("echo_back", "回显输入文本，测试工具",
                    "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}",
                    (exchange, request) -> CallToolResult.builder()
                            .addTextContent("回声: " + request.arguments().get("text")).isError(false).build());

            Path portFile = PathManager.getSystemDir().resolve("mcp/mcp-bridge-test.url");
            assertTrue(java.nio.file.Files.isRegularFile(portFile), "the port file the bridge needs must exist first");

            bridge = new ProcessBuilder(javaExecutable(), "-cp", System.getProperty("java.class.path"),
                    McpStdioBridge.class.getName(), portFile.toString()).start();
            try (BufferedWriter toBridge = new BufferedWriter(
                    new OutputStreamWriter(bridge.getOutputStream(), StandardCharsets.UTF_8));
                 BufferedReader fromBridge = new BufferedReader(
                         new InputStreamReader(bridge.getInputStream(), StandardCharsets.UTF_8))) {

                send(toBridge, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                        + "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                        + "\"clientInfo\":{\"name\":\"bridge-test\",\"version\":\"0\"}}}");
                JsonNode init = MAPPER.readTree(fromBridge.readLine());
                assertTrue(init.path("result").path("serverInfo").path("name").asText()
                        .contains("mcp-bridge-test"), "the handshake should return serverInfo: " + init);

                // A notification has no id and no response, so the next line read answers tools/list.
                send(toBridge, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
                send(toBridge, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
                JsonNode list = MAPPER.readTree(fromBridge.readLine());
                assertEquals("echo_back", list.path("result").path("tools").get(0).path("name").asText());

                send(toBridge, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"echo_back\",\"arguments\":{\"text\":\"桥接打通\"}}}");
                JsonNode call = MAPPER.readTree(fromBridge.readLine());
                assertEquals("回声: 桥接打通",
                        call.path("result").path("content").get(0).path("text").asText());
                assertFalse(call.path("result").path("isError").asBoolean(true));
            }
            assertEquals(0, bridge.waitFor(), "the bridge should exit normally once stdin closes");
            bridge = null;
        });
    }

    private static void send(BufferedWriter toBridge, String line) throws Exception {
        toBridge.write(line);
        toBridge.newLine();
        toBridge.flush();
    }

    private static String javaExecutable() {
        String exe = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", exe).toString();
    }
}
