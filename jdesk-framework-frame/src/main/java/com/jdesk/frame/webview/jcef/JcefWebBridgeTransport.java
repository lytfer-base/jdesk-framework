package com.jdesk.frame.webview.jcef;

import com.jdesk.frame.webview.WebViewEvent;
import com.jdesk.frame.webview.WebViewException;
import com.jdesk.frame.webview.WebViewListener;
import com.jdesk.frame.webview.bridge.WebBridgeError;
import com.jdesk.frame.webview.bridge.WebBridgeErrorCode;
import com.jdesk.frame.webview.bridge.WebBridgeEvent;
import com.jdesk.frame.webview.bridge.WebBridgeRequest;
import com.jdesk.frame.webview.bridge.WebBridgeResponse;
import com.jdesk.frame.webview.bridge.spi.WebBridgeTransport;
import com.jdesk.frame.webview.bridge.spi.WebBridgeTransportContext;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * WebBridge transport over the JCEF MessageRouter: the page sends requests through {@code window.jdeskQuery} and gets
 * responses through the query callback. Requests are decoded and dispatched in arrival order on a serial thread per
 * view.
 * Don't move dispatching back to the CEF UI thread: every JCEF window shares it, so one slow handler stalls them all.
 */
final class JcefWebBridgeTransport implements WebBridgeTransport {
    private static final AtomicInteger THREAD_IDS = new AtomicInteger();

    /** Idle time after which the dispatch thread exits; the next request starts a new one. */
    private static final long DISPATCH_IDLE_SECONDS = 30L;

    private final JcefBridgeWebView view;
    private final ThreadPoolExecutor dispatcher;
    private volatile WebBridgeTransportContext context;
    private WebViewListener bootstrapListener;
    private volatile boolean disposed;

    JcefWebBridgeTransport(JcefBridgeWebView view) {
        this.view = Objects.requireNonNull(view, "view");
        this.dispatcher = new ThreadPoolExecutor(1, 1, DISPATCH_IDLE_SECONDS, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), runnable -> {
                    Thread thread = new Thread(runnable, "jdesk-jcef-bridge-" + THREAD_IDS.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
        this.dispatcher.allowCoreThreadTimeOut(true);
    }

    /** Binds the session context and attaches this transport to its JCEF view. */
    @Override
    public void attach(WebBridgeTransportContext context) {
        this.context = Objects.requireNonNull(context, "context");
        view.attachBridgeTransport(this);
    }

    /** Delivers a response through the page's {@code __jdeskBridgeReceive}. */
    @Override
    public void send(WebBridgeResponse response) {
        executeReceive(response);
    }

    /** Delivers an event through the page's {@code __jdeskBridgeEmit}. */
    @Override
    public void sendEvent(WebBridgeEvent event) {
        executeEvent(event);
    }

    /** Injects the bootstrap script now and again after every finished page load. */
    @Override
    public void injectBootstrap() {
        if (bootstrapListener == null) {
            bootstrapListener = event -> {
                if (event.getType() == WebViewEvent.Type.LOAD_FINISHED) {
                    injectNow();
                }
            };
            context.webView().addListener(bootstrapListener);
        }
        injectNow();
    }

    /**
     * Takes a raw request from the MessageRouter and always handles it, answering asynchronously through
     * {@code responseConsumer}. Called on the CEF UI thread; it only queues the request for this view's dispatcher.
     */
    boolean receive(String rawRequest, Consumer<String> responseConsumer) {
        if (disposed) {
            responseConsumer.accept(disposedResponse());
            return true;
        }
        try {
            dispatcher.execute(() -> dispatch(rawRequest, responseConsumer));
        } catch (RejectedExecutionException disposedMeanwhile) {
            responseConsumer.accept(disposedResponse());
        }
        return true;
    }

    /** Detaches from the view and removes the load listener; requests already queued are still answered. */
    @Override
    public void dispose() {
        disposed = true;
        dispatcher.shutdown();
        if (bootstrapListener != null && context != null) {
            context.webView().removeListener(bootstrapListener);
        }
        view.detachBridgeTransport(this);
    }

    /** On the dispatch thread: decodes the request, passes it to the session and encodes the result for CEF. */
    private void dispatch(String rawRequest, Consumer<String> responseConsumer) {
        WebBridgeRequest request;
        try {
            request = context.codec().decodeRequest(rawRequest, "", view.bridgeUrl());
        } catch (IOException | IllegalArgumentException error) {
            responseConsumer.accept(context.codec().encodeResponse(context.codec().decodeFailure(rawRequest, error)));
            return;
        } catch (WebViewException viewDisposed) {
            responseConsumer.accept(disposedResponse());
            return;
        }
        context.inbound().receive(request).whenComplete((response, failure) -> {
            WebBridgeResponse actualResponse = failure == null
                    ? response
                    : WebBridgeResponse.failure(request.id(),
                    WebBridgeError.of(WebBridgeErrorCode.HANDLER_FAILED, failureMessage(failure)));
            responseConsumer.accept(context.codec().encodeResponse(actualResponse));
        });
    }

    private String disposedResponse() {
        return context.codec().encodeResponse(WebBridgeResponse.failure("",
                WebBridgeError.of(WebBridgeErrorCode.BRIDGE_DISPOSED, "WebBridge transport is disposed.")));
    }

    /** Injects the bootstrap script; it is dropped while the native browser doesn't exist, so LOAD_FINISHED retries. */
    private void injectNow() {
        if (disposed || context == null) {
            return;
        }
        String script = context.bootstrapScript().create(context.options(), "window.jdeskQuery");
        try {
            view.executeBridgeJavaScript(script);
        } catch (WebViewException ignored) {
            // The view is disposed and the page is going away.
        }
    }

    private void executeReceive(WebBridgeResponse response) {
        if (disposed || context == null) {
            return;
        }
        String json = context.codec().encodeResponse(response);
        view.executeBridgeJavaScript("window.__jdeskBridgeReceive(" + json + ");");
    }

    private void executeEvent(WebBridgeEvent event) {
        if (disposed || context == null) {
            return;
        }
        String json = context.codec().encodeEvent(event);
        view.executeBridgeJavaScript("if (window.__jdeskBridgeEmit) { window.__jdeskBridgeEmit(" + json + "); }");
    }

    private static String failureMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null ? failure.getClass().getName() : message;
    }
}

/** What a JCEF view exposes to its WebBridge transport, keeping the transport out of the service's internals. */
interface JcefBridgeWebView {
    void attachBridgeTransport(JcefWebBridgeTransport transport);

    void detachBridgeTransport(JcefWebBridgeTransport transport);

    boolean handleBridgeQuery(String request, Consumer<String> responseConsumer);

    void executeBridgeJavaScript(String script);

    String bridgeUrl();
}
