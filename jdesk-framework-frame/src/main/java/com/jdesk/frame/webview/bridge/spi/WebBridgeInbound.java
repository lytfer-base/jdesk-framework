package com.jdesk.frame.webview.bridge.spi;

import com.jdesk.frame.webview.bridge.WebBridgeRequest;
import com.jdesk.frame.webview.bridge.WebBridgeResponse;

import java.util.concurrent.CompletionStage;

/** Where a transport hands a decoded {@link WebBridgeRequest} to the session for dispatch. */
@FunctionalInterface
public interface WebBridgeInbound {

    /** Receives a page request and completes with its response. */
    CompletionStage<WebBridgeResponse> receive(WebBridgeRequest request);
}
