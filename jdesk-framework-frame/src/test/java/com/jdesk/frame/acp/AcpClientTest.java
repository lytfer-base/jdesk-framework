package com.jdesk.frame.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.jdesk.core.disposer.Disposable;
import com.jdesk.core.disposer.Disposer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protocol tests for {@link AcpClient} against a {@link FakeAcpAgent} child process: handshake, streamed notifications,
 * the four permission replies, -32601 for unhandled agent requests, protocol errors and process cleanup.
 */
class AcpClientTest {

    @TempDir
    Path tempDir;

    private Disposable root;
    private AcpAgentProcess process;
    private AcpClient client;
    private Path logFile;

    @AfterEach
    void tearDown() {
        if (root != null) {
            Disposer.dispose(root);
        }
    }

    @Test
    void initializeReturnsAgentInfo() {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            startClient();
            JsonNode result = client.initialize("acp-test", "0.1");
            assertEquals(1, result.path("protocolVersion").asInt());
            assertEquals("fake-acp", result.path("agentInfo").path("name").asText());
        });
    }

    @Test
    void promptDeliversStreamChunksThenStopReason() {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            startClient();
            client.initialize("acp-test", "0.1");
            String sessionId = client.newSession(tempDir.toString(), null).path("sessionId").asText();

            ConcurrentLinkedQueue<String> updates = new ConcurrentLinkedQueue<>();
            client.setNotificationListener((method, params) -> {
                if ("session/update".equals(method)) {
                    JsonNode update = params.path("update");
                    updates.add(update.path("sessionUpdate").asText()
                            + ":" + update.path("content").path("text").asText());
                }
            });

            JsonNode result = client.prompt(sessionId, "总结一下", 30);
            assertEquals("end_turn", result.path("stopReason").asText());
            assertEquals(List.of(
                    "agent_thought_chunk:思考中",
                    "agent_message_chunk:你好",
                    "agent_message_chunk:世界"), List.copyOf(updates));
        });
    }

    @Test
    void permissionRequestCarriesFourOptionsAndSelectionIsSentBack() {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            startClient();
            client.initialize("acp-test", "0.1");
            String sessionId = client.newSession(tempDir.toString(), null).path("sessionId").asText();

            AtomicReference<AcpPermissionRequest> seen = new AtomicReference<>();
            client.setPermissionHandler(request -> {
                seen.set(request);
                return "allow_once";
            });

            JsonNode result = client.prompt(sessionId, "帮我写个文件", 30);
            assertEquals("end_turn", result.path("stopReason").asText());

            AcpPermissionRequest request = seen.get();
            assertEquals("sess-1", request.sessionId());
            assertEquals("写文件", request.toolCallTitle());
            assertEquals(List.of("allow_once", "allow_always", "reject_once", "reject_always"),
                    request.options().stream().map(AcpPermissionRequest.Option::optionId).toList());

            String logged = waitForLog();
            assertTrue(logged.contains("selected"), "the reply should be selected: " + logged);
            assertTrue(logged.contains("allow_once"), "the reply should carry the chosen optionId: " + logged);
            assertTrue(result.path("permissionReply").asText().contains("allow_once"));
        });
    }

    @Test
    void unhandledAgentRequestGetsMinus32601() {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            startClient();
            client.initialize("acp-test", "0.1");
            String sessionId = client.newSession(tempDir.toString(), null).path("sessionId").asText();

            client.prompt(sessionId, "帮我读文件", 30);
            String logged = waitForLog();
            assertTrue(logged.contains("-32601"), "an unhandled agent request should get -32601: " + logged);
        });
    }

    @Test
    void processExitFailsInflightRequestImmediately() {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            startClient();
            client.initialize("acp-test", "0.1");
            CountDownLatch settled = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread waiter = new Thread(() -> {
                try {
                    client.request("test/hang", client.mapper().createObjectNode(), 300);
                } catch (Throwable t) {
                    failure.set(t);
                } finally {
                    settled.countDown();
                }
            }, "acp-inflight-waiter");
            waiter.setDaemon(true);
            waiter.start();
            Thread.sleep(500); // Let the request go out and block on its reply before killing the process.

            ProcessHandle.of(process.pid()).orElseThrow().destroy();
            assertTrue(settled.await(15, TimeUnit.SECONDS),
                    "a process exit must fail the in-flight request at once, not after the 300 s timeout");
            assertNotNull(failure.get());
        });
    }

    @Test
    void loadListDeleteSessionContract() {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            startClient();
            client.initialize("acp-test", "0.1");

            JsonNode list = client.listSessions(tempDir.toString());
            assertEquals("sess-1", list.path("sessions").get(0).path("sessionId").asText());

            ConcurrentLinkedQueue<String> updates = new ConcurrentLinkedQueue<>();
            client.setNotificationListener((method, params) -> {
                if ("session/update".equals(method)) {
                    JsonNode update = params.path("update");
                    updates.add(update.path("sessionUpdate").asText()
                            + ":" + update.path("content").path("text").asText());
                }
            });
            JsonNode loaded = client.loadSession("sess-1", tempDir.toString(), null);
            assertEquals("auto", loaded.path("modes").path("currentModeId").asText(),
                    "the session/load result must include modes");
            assertEquals(List.of("user_message_chunk:历史提问", "agent_message_chunk:历史回答"),
                    List.copyOf(updates), "a resumed session's history arrives as session/update notifications");

            client.deleteSession("sess-1"); // A normal reply means deleted; a protocol error throws.
        });
    }

    @Test
    void cancelSessionSettlesInflightPromptAsCancelled() {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            startClient();
            client.initialize("acp-test", "0.1");
            String sessionId = client.newSession(tempDir.toString(), null).path("sessionId").asText();

            AtomicReference<JsonNode> outcome = new AtomicReference<>();
            Thread prompter = new Thread(() ->
                    outcome.set(client.prompt(sessionId, "慢工出细活", 60)), "acp-cancel-prompter");
            prompter.setDaemon(true);
            prompter.start();
            Thread.sleep(500); // Let the prompt go out and the fake agent hold the turn before cancelling.

            client.cancelSession(sessionId);
            prompter.join(TimeUnit.SECONDS.toMillis(30));
            assertNotNull(outcome.get(), "a cancelled prompt must finish instead of waiting for the timeout");
            assertEquals("cancelled", outcome.get().path("stopReason").asText());

            String logged = waitForLog();
            JsonNode cancelFrame = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                    logged.lines().filter(l -> l.contains("session/cancel")).findFirst().orElseThrow());
            assertFalse(cancelFrame.has("id"), "session/cancel must be a notification without an id: " + cancelFrame);
            assertEquals(sessionId, cancelFrame.path("params").path("sessionId").asText());
        });
    }

    @Test
    void protocolErrorSurfacesAsAcpRequestException() {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            startClient();
            client.initialize("acp-test", "0.1");
            // Bypass the facade and send session/new without cwd; the fake agent replies -32603.
            AcpClient.AcpRequestException error = assertThrows(AcpClient.AcpRequestException.class,
                    () -> client.request("session/new", client.mapper().createObjectNode(), 10));
            assertEquals(-32603, error.code());
        });
    }

    /**
     * httpMcpServerEntry must send {@code headers} as an empty array; without the field, goose's untagged
     * deserialization silently drops the whole server, logging only a warning.
     */
    @Test
    void httpMcpServerEntryCarriesEmptyHeaders() {
        JsonNode entry = new AcpClient(null).httpMcpServerEntry("myapp", "http://127.0.0.1:1/mcp");
        assertEquals("http", entry.path("type").asText());
        assertEquals("myapp", entry.path("name").asText());
        assertTrue(entry.has("headers") && entry.path("headers").isArray(),
                "without headers, goose silently drops the whole mcpServers entry");
    }

    @Test
    void disposeReapsProcess() {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            startClient();
            assertTrue(process.isAlive());
            Disposer.dispose(root);
            root = null;
            assertFalse(process.isAlive(), "the child process must be gone after dispose");
        });
    }

    /** Starts the fake agent process and attaches the client; the process joins the test's Disposer tree. */
    private void startClient() {
        logFile = tempDir.resolve("fake-agent-replies.log");
        String exe = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        String java = Path.of(System.getProperty("java.home"), "bin", exe).toString();
        // Don't use tempDir as the working directory: on Windows the child's CWD locks it and JUnit cleanup fails.
        process = new AcpAgentProcess(
                List.of(java, "-cp", System.getProperty("java.class.path"),
                        FakeAcpAgent.class.getName(), logFile.toString()),
                Path.of(System.getProperty("user.dir")), Map.of());
        root = Disposer.newDisposable("acp-client-test");
        Disposer.register(root, process);
        process.start();
        client = new AcpClient(process);
        client.attach();
    }

    /** Polls briefly for the reply log, which the fake agent writes asynchronously. */
    private String waitForLog() throws Exception {
        for (int i = 0; i < 100; i++) {
            if (Files.isRegularFile(logFile)) {
                String content = Files.readString(logFile);
                if (!content.isBlank()) {
                    return content;
                }
            }
            Thread.sleep(100);
        }
        return "<no log written>";
    }
}
