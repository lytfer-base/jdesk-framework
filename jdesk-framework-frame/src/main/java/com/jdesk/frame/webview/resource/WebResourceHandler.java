package com.jdesk.frame.webview.resource;

import java.util.concurrent.CompletionStage;

/**
 * Handles one custom-scheme resource request.
 */
@FunctionalInterface
public interface WebResourceHandler {
    CompletionStage<WebResourceResponse> handle(WebResourceRequest request);
}
