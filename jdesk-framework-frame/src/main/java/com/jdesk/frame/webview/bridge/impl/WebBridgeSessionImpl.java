package com.jdesk.frame.webview.bridge.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.jdesk.core.i18n.LocalizedException;
import com.jdesk.core.io.Cancellation;
import com.jdesk.frame.webview.bridge.WebBridgeError;
import com.jdesk.frame.webview.bridge.WebBridgeErrorCode;
import com.jdesk.frame.webview.bridge.WebBridgeEvent;
import com.jdesk.frame.webview.bridge.WebBridgeHandler;
import com.jdesk.frame.webview.bridge.WebBridgeOptions;
import com.jdesk.frame.webview.bridge.WebBridgeRequest;
import com.jdesk.frame.webview.bridge.WebBridgeResponse;
import com.jdesk.frame.webview.bridge.WebBridgeSession;
import com.jdesk.frame.webview.bridge.spi.WebBridgeTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runtime {@link WebBridgeSession}: manages handlers and dispatches requests, independent of any browser backend. */
final class WebBridgeSessionImpl implements WebBridgeSession {
    private static final Logger LOG = LoggerFactory.getLogger(WebBridgeSessionImpl.class);

    private final WebBridgeTransport transport;
    private final WebBridgeOptions options;
    private final WebBridgeCodec codec = new WebBridgeCodec();
    private final WebBridgeBootstrapScript bootstrapScript = new WebBridgeBootstrapScript();
    private final ConcurrentMap<String, WebBridgeHandler> handlers = new ConcurrentHashMap<>();
    private final AtomicBoolean disposed = new AtomicBoolean();

    WebBridgeSessionImpl(WebBridgeTransport transport, WebBridgeOptions options) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.options = options == null ? WebBridgeOptions.defaults() : options;
    }

    WebBridgeCodec codec() {
        return codec;
    }

    WebBridgeBootstrapScript bootstrapScript() {
        return bootstrapScript;
    }

    WebBridgeOptions options() {
        return options;
    }

    /** Registers a method; registering the same name again replaces its handler. */
    @Override
    public void register(String method, WebBridgeHandler handler) {
        String key = normalizeMethod(method);
        handlers.put(key, Objects.requireNonNull(handler, "handler"));
    }

    /** Removes a method; later calls from the page get METHOD_NOT_FOUND. */
    @Override
    public void unregister(String method) {
        String key = method == null ? "" : method.trim();
        if (!key.isEmpty()) {
            handlers.remove(key);
        }
    }

    @Override
    public boolean hasHandler(String method) {
        String key = method == null ? "" : method.trim();
        return !key.isEmpty() && handlers.containsKey(key);
    }

    /** Sends an event to the page; ignored once the session is disposed. */
    @Override
    public void emit(String event, JsonNode payload) {
        WebBridgeEvent message = new WebBridgeEvent(event, payload);
        if (!disposed.get()) {
            transport.sendEvent(message);
        }
    }

    /** Dispatches a request to its handler, turning every failure into a {@link WebBridgeResponse}. */
    @Override
    public CompletionStage<WebBridgeResponse> dispatch(WebBridgeRequest request) {
        String requestId = request == null ? "" : request.id();
        if (disposed.get()) {
            return CompletableFuture.completedFuture(failure(requestId,
                    WebBridgeErrorCode.BRIDGE_DISPOSED,
                    "WebBridge session is disposed."));
        }
        if (request == null) {
            return CompletableFuture.completedFuture(failure("",
                    WebBridgeErrorCode.INVALID_REQUEST,
                    "WebBridge request is required."));
        }
        try {
            request.validate();
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture(failure(request.id(),
                    WebBridgeErrorCode.INVALID_REQUEST,
                    e.getMessage()));
        }

        WebBridgeHandler handler = handlers.get(request.method());
        if (handler == null) {
            return CompletableFuture.completedFuture(failure(request.id(),
                    WebBridgeErrorCode.METHOD_NOT_FOUND,
                    "No handler registered for method: " + request.method()));
        }

        try {
            CompletionStage<WebBridgeResponse> stage = handler.handle(request);
            if (stage == null) {
                LOG.warn("webbridge.dispatch.nullStage method={}", request.method());
                return CompletableFuture.completedFuture(failure(request.id(),
                        WebBridgeErrorCode.HANDLER_FAILED,
                        "WebBridge handler returned null."));
            }
            return stage.handle((response, error) -> {
                if (error != null) {
                    logUnhandled(request.method(), error);
                    return WebBridgeResponse.failure(request.id(), toError(error));
                }
                if (response == null) {
                    // Passing null on would fail when encoding, and the page's promise would never settle.
                    LOG.warn("webbridge.dispatch.nullResponse method={}", request.method());
                    return failure(request.id(), WebBridgeErrorCode.HANDLER_FAILED,
                            "WebBridge handler completed without a response.");
                }
                return response;
            });
        } catch (RuntimeException error) {
            logUnhandled(request.method(), error);
            return CompletableFuture.completedFuture(WebBridgeResponse.failure(request.id(), toError(error)));
        }
    }

    /**
     * Logs a failure the handler didn't catch: a cancellation anywhere in the cause chain at DEBUG, a rejection based
     * on user state as one WARN line without a stack trace, anything else as WARN with the stack trace.
     * Log the method name only: params hold user content and disk paths.
     */
    private static void logUnhandled(String method, Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null
                ? error.getCause() : error;
        if (Cancellation.isCancellation(cause)) {
            LOG.debug("webbridge.dispatch.cancelled method={}", method);
            return;
        }
        LocalizedException rejection = LocalizedException.rejectionOf(cause);
        if (rejection != null) {
            LOG.warn("webbridge.dispatch.rejected method={} reason={} message={}",
                    method, rejection.reason(), rejection.getMessage());
            return;
        }
        LOG.warn("webbridge.dispatch.unhandled method={}", method, cause);
    }

    /** Clears the handlers and disposes the browser transport. */
    @Override
    public void dispose() {
        if (disposed.compareAndSet(false, true)) {
            handlers.clear();
            transport.dispose();
        }
    }

    private static String normalizeMethod(String method) {
        String key = Objects.toString(method, "").trim();
        if (key.isEmpty()) {
            throw new IllegalArgumentException("WebBridge method is required.");
        }
        return key;
    }

    private static WebBridgeResponse failure(String id, WebBridgeErrorCode code, String message) {
        return WebBridgeResponse.failure(id, WebBridgeError.of(code, message));
    }

    private static WebBridgeError toError(Throwable error) {
        return WebBridgeError.of(WebBridgeErrorCode.HANDLER_FAILED, error);
    }
}
