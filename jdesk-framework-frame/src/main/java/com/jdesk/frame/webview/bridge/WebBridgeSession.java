package com.jdesk.frame.webview.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.jdesk.core.disposer.Disposable;

import java.util.concurrent.CompletionStage;

/**
 * WebBridge session of one WebView page: maps method names to Java handlers, emits events to the page and detaches
 * the backend transport when disposed. A WebView usually needs one session.
 */
public interface WebBridgeSession extends Disposable {

    /** Registers a method the page can call, such as {@code demo.saveDraft}. */
    void register(String method, WebBridgeHandler handler);

    /** Removes a method's handler. */
    void unregister(String method);

    /** Returns whether the method has a handler; mainly for tests and diagnostics. */
    boolean hasHandler(String method);

    /** Sends a fire-and-forget event; the page subscribes with {@code window.jdeskBridge.on(event, handler)}. */
    void emit(String event, JsonNode payload);

    /**
     * Dispatches a decoded page request; transports call it, while application code only uses
     * {@link #register(String, WebBridgeHandler)}.
     */
    CompletionStage<WebBridgeResponse> dispatch(WebBridgeRequest request);
}
