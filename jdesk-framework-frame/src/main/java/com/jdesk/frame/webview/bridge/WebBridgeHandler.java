package com.jdesk.frame.webview.bridge;

import java.util.concurrent.CompletionStage;

/** Handles a WebBridge method, completing asynchronously with a success or failure response. */
@FunctionalInterface
public interface WebBridgeHandler {

    /** Handles one call from the page. */
    CompletionStage<WebBridgeResponse> handle(WebBridgeRequest request);
}
