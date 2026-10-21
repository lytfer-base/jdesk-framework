package com.jdesk.frame.webview.bridge.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.jdesk.core.i18n.LocalizedException;
import com.jdesk.frame.webview.bridge.WebBridgeErrorCode;
import com.jdesk.frame.webview.bridge.WebBridgeEvent;
import com.jdesk.frame.webview.bridge.WebBridgeOptions;
import com.jdesk.frame.webview.bridge.WebBridgeRequest;
import com.jdesk.frame.webview.bridge.WebBridgeResponse;
import com.jdesk.frame.webview.bridge.spi.WebBridgeTransport;
import com.jdesk.frame.webview.bridge.spi.WebBridgeTransportContext;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebBridgeSessionImplTest {

    @Test
    void dispatchCallsRegisteredHandler() {
        WebBridgeSessionImpl session = new WebBridgeSessionImpl(new RecordingTransport(), WebBridgeOptions.defaults());
        session.register("records.create", request -> CompletableFuture.completedFuture(
                WebBridgeResponse.success(request.id(), JsonNodeFactory.instance.objectNode().put("saved", true))));

        WebBridgeResponse response = session.dispatch(request("1", "records.create")).toCompletableFuture().join();

        assertTrue(response.ok());
        assertTrue(response.result().get("saved").asBoolean());
    }

    @Test
    void missingHandlerReturnsMethodNotFound() {
        WebBridgeSessionImpl session = new WebBridgeSessionImpl(new RecordingTransport(), WebBridgeOptions.defaults());

        WebBridgeResponse response = session.dispatch(request("1", "records.missing")).toCompletableFuture().join();

        assertFalse(response.ok());
        assertEquals(WebBridgeErrorCode.METHOD_NOT_FOUND, response.error().code());
    }

    @Test
    void handlerExceptionReturnsHandlerFailed() {
        WebBridgeSessionImpl session = new WebBridgeSessionImpl(new RecordingTransport(), WebBridgeOptions.defaults());
        session.register("records.create", request -> {
            throw new IllegalStateException("boom");
        });

        WebBridgeResponse response = session.dispatch(request("1", "records.create")).toCompletableFuture().join();

        assertFalse(response.ok());
        assertEquals(WebBridgeErrorCode.HANDLER_FAILED, response.error().code());
    }

    @Test
    void aRejectionByUserStateLogsOneWarnLineWithoutStackTrace() {
        WebBridgeSessionImpl session = new WebBridgeSessionImpl(new RecordingTransport(), WebBridgeOptions.defaults());
        session.register("projects.readFile", request -> CompletableFuture.failedFuture(
                new LocalizedException("projects.open.fileMissing", Map.of(), "File no longer exists")));
        session.register("projects.save", request -> {
            throw new IllegalStateException("boom");
        });
        Logger logger = (Logger) LoggerFactory.getLogger(WebBridgeSessionImpl.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        Level previous = logger.getLevel();
        logs.start();
        logger.addAppender(logs);
        logger.setLevel(Level.WARN);
        try {
            session.dispatch(request("1", "projects.readFile")).toCompletableFuture().join();
            session.dispatch(request("2", "projects.save")).toCompletableFuture().join();
        } finally {
            logger.setLevel(previous);
            logger.detachAppender(logs);
        }

        assertEquals(2, logs.list.size());
        ILoggingEvent rejected = logs.list.get(0);
        assertEquals(Level.WARN, rejected.getLevel());
        assertEquals("webbridge.dispatch.rejected method=projects.readFile reason=projects.open.fileMissing"
                + " message=File no longer exists", rejected.getFormattedMessage());
        assertNull(rejected.getThrowableProxy());
        ILoggingEvent unhandled = logs.list.get(1);
        assertEquals(Level.WARN, unhandled.getLevel());
        assertEquals("webbridge.dispatch.unhandled method=projects.save", unhandled.getFormattedMessage());
        assertNotNull(unhandled.getThrowableProxy());
    }

    /** A cancellation anywhere in the cause chain, even wrapped in a LocalizedException, logs at DEBUG, not WARN. */
    @Test
    void aCancellationAnywhereInTheCauseChainIsLoggedAtDebug() {
        WebBridgeSessionImpl session = new WebBridgeSessionImpl(new RecordingTransport(), WebBridgeOptions.defaults());
        session.register("projects.importDroppedFile", request -> {
            throw new CompletionException(new LocalizedException("projects.error.importCopyFailed", Map.of(),
                    "Import copy failed", new InterruptedIOException("Cancelled while copying a stream")));
        });
        session.register("projects.readFile", request -> CompletableFuture.supplyAsync(() -> {
            throw new LocalizedException("projects.error.readFailed", Map.of(), "Read failed",
                    new InterruptedIOException("Cancelled while reading"));
        }));
        Logger logger = (Logger) LoggerFactory.getLogger(WebBridgeSessionImpl.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        Level previous = logger.getLevel();
        logs.start();
        logger.addAppender(logs);
        logger.setLevel(Level.DEBUG);
        try {
            session.dispatch(request("1", "projects.importDroppedFile")).toCompletableFuture().join();
            session.dispatch(request("2", "projects.readFile")).toCompletableFuture().join();
        } finally {
            logger.setLevel(previous);
            logger.detachAppender(logs);
        }

        assertEquals(List.of(), logs.list.stream().filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
                .map(ILoggingEvent::getFormattedMessage).toList());
        assertEquals(List.of("webbridge.dispatch.cancelled method=projects.importDroppedFile",
                        "webbridge.dispatch.cancelled method=projects.readFile"),
                logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
        assertTrue(logs.list.stream().allMatch(event -> event.getThrowableProxy() == null), "no stack traces");
    }

    @Test
    void localizedExceptionBecomesReasonAndParams() {
        WebBridgeSessionImpl session = new WebBridgeSessionImpl(new RecordingTransport(), WebBridgeOptions.defaults());
        session.register("records.save", request -> {
            throw new LocalizedException("demo.reason", Map.of("name", "a.md"), "save failed");
        });

        WebBridgeResponse response = session.dispatch(request("1", "records.save")).toCompletableFuture().join();

        assertFalse(response.ok());
        assertEquals(WebBridgeErrorCode.HANDLER_FAILED, response.error().code());
        assertEquals("save failed", response.error().message());
        assertEquals("demo.reason", response.error().details().get("reason").asText());
        assertEquals("a.md", response.error().details().get("params").get("name").asText());
    }

    @Test
    void localizedExceptionFromFailedFutureAlsoCarriesReason() {
        WebBridgeSessionImpl session = new WebBridgeSessionImpl(new RecordingTransport(), WebBridgeOptions.defaults());
        session.register("records.save", request -> CompletableFuture.failedFuture(
                new LocalizedException("demo.reason", Map.of(), "save failed")));

        WebBridgeResponse response = session.dispatch(request("2", "records.save")).toCompletableFuture().join();

        assertEquals("demo.reason", response.error().details().get("reason").asText());
    }

    /**
     * Params reach the page as the text Java would show, with count kept as a number; a type Jackson can't handle
     * must not leave the request unanswered.
     */
    @Test
    void localizedParamsReachThePageAsTheTextJavaShows() {
        WebBridgeSessionImpl session = new WebBridgeSessionImpl(new RecordingTransport(), WebBridgeOptions.defaults());
        Path file = Path.of("notes", "a.md");
        session.register("records.save", request -> {
            throw new LocalizedException("demo.reason",
                    Map.of("count", 2, "day", LocalDate.of(2026, 9, 28), "file", file), "save failed");
        });

        WebBridgeResponse response = session.dispatch(request("3", "records.save")).toCompletableFuture().join();

        JsonNode params = response.error().details().get("params");
        assertTrue(params.get("count").isNumber());
        assertEquals("2026-09-28", params.get("day").asText());
        assertEquals(file.toString(), params.get("file").asText());
    }

    /** An async handler failing in a dependent stage arrives wrapped in a CompletionException. */
    @Test
    void localizedExceptionFromDependentStageCarriesReason() {
        WebBridgeSessionImpl session = new WebBridgeSessionImpl(new RecordingTransport(), WebBridgeOptions.defaults());
        session.register("records.save", request -> CompletableFuture.supplyAsync(() -> {
            throw new LocalizedException("demo.reason", Map.of(), "save failed");
        }));

        WebBridgeResponse response = session.dispatch(request("4", "records.save")).toCompletableFuture().join();

        assertEquals("demo.reason", response.error().details().path("reason").asText());
        assertEquals("save failed", response.error().message());
    }

    @Test
    void aParamThatCannotBeRenderedStillAnswersTheRequest() {
        WebBridgeSessionImpl session = new WebBridgeSessionImpl(new RecordingTransport(), WebBridgeOptions.defaults());
        Object unrenderable = new Object() {
            @Override
            public String toString() {
                throw new IllegalStateException("toString failed");
            }
        };
        session.register("records.save", request -> {
            throw new LocalizedException("demo.reason", Map.of("name", unrenderable), "save failed");
        });

        WebBridgeResponse response = session.dispatch(request("5", "records.save")).toCompletableFuture().join();

        assertEquals("5", response.id());
        assertFalse(response.ok());
        assertEquals(WebBridgeErrorCode.HANDLER_FAILED, response.error().code());
        assertEquals("save failed", response.error().message());
    }

    /** A handler completing with null still gets a failure with the request id, so the page's Promise settles. */
    @Test
    void aHandlerCompletingWithoutAResponseStillAnswersTheRequest() {
        WebBridgeSessionImpl session = new WebBridgeSessionImpl(new RecordingTransport(), WebBridgeOptions.defaults());
        session.register("records.create", request -> CompletableFuture.completedFuture(null));

        WebBridgeResponse response = session.dispatch(request("7", "records.create")).toCompletableFuture().join();

        assertEquals("7", response.id());
        assertFalse(response.ok());
        assertEquals(WebBridgeErrorCode.HANDLER_FAILED, response.error().code());
    }

    @Test
    void disposeClearsHandlersAndDisposesTransport() {
        RecordingTransport transport = new RecordingTransport();
        WebBridgeSessionImpl session = new WebBridgeSessionImpl(transport, WebBridgeOptions.defaults());
        session.register("records.create", request -> CompletableFuture.completedFuture(
                WebBridgeResponse.success(request.id(), JsonNodeFactory.instance.objectNode())));

        session.dispose();

        assertTrue(transport.disposed);
        assertFalse(session.hasHandler("records.create"));
    }

    @Test
    void disposedSessionRejectsNewRequests() {
        WebBridgeSessionImpl session = new WebBridgeSessionImpl(new RecordingTransport(), WebBridgeOptions.defaults());
        session.dispose();

        WebBridgeResponse response = session.dispatch(request("1", "records.create")).toCompletableFuture().join();

        assertFalse(response.ok());
        assertEquals(WebBridgeErrorCode.BRIDGE_DISPOSED, response.error().code());
    }

    @Test
    void emitSendsEventThroughTransport() {
        RecordingTransport transport = new RecordingTransport();
        WebBridgeSessionImpl session = new WebBridgeSessionImpl(transport, WebBridgeOptions.defaults());

        session.emit("records.changed", JsonNodeFactory.instance.objectNode().put("count", 3));

        assertEquals(1, transport.events.size());
        assertEquals("records.changed", transport.events.get(0).event());
        assertEquals(3, transport.events.get(0).payload().get("count").asInt());
    }

    @Test
    void emitRejectsBlankEventName() {
        WebBridgeSessionImpl session = new WebBridgeSessionImpl(new RecordingTransport(), WebBridgeOptions.defaults());

        assertThrows(IllegalArgumentException.class,
                () -> session.emit(" ", JsonNodeFactory.instance.objectNode()));
    }

    @Test
    void disposedSessionIgnoresEvents() {
        RecordingTransport transport = new RecordingTransport();
        WebBridgeSessionImpl session = new WebBridgeSessionImpl(transport, WebBridgeOptions.defaults());
        session.dispose();

        session.emit("records.changed", JsonNodeFactory.instance.objectNode().put("count", 3));

        assertTrue(transport.events.isEmpty());
    }

    private static WebBridgeRequest request(String id, String method) {
        return new WebBridgeRequest(id, method, JsonNodeFactory.instance.objectNode(), "", "");
    }

    private static final class RecordingTransport implements WebBridgeTransport {
        private boolean disposed;
        private final List<WebBridgeResponse> sent = new ArrayList<>();
        private final List<WebBridgeEvent> events = new ArrayList<>();

        @Override
        public void attach(WebBridgeTransportContext context) {
        }

        @Override
        public void send(WebBridgeResponse response) {
            sent.add(response);
        }

        @Override
        public void sendEvent(WebBridgeEvent event) {
            events.add(event);
        }

        @Override
        public void injectBootstrap() {
        }

        @Override
        public void dispose() {
            disposed = true;
        }
    }
}
