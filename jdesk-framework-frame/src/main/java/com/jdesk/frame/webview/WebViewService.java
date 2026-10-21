package com.jdesk.frame.webview;

import java.util.concurrent.CompletableFuture;

/**
 * Application-facing service for creating embedded browser views.
 */
public interface WebViewService {

    JdfWebView createWebView();

    JdfWebView createWebView(WebViewOptions options);

    default CompletableFuture<JdfWebView> createWebViewAsync() {
        return createWebViewAsync(WebViewOptions.defaults());
    }

    CompletableFuture<JdfWebView> createWebViewAsync(WebViewOptions options);

    CompletableFuture<WebViewWarmupResult> warmUpAsync();

    CompletableFuture<WebViewWarmupResult> warmUpViewAsync();

    boolean isEngineReady();

    WebViewBackendStatus getBackendStatus();

    default String getBackendName() {
        return getClass().getSimpleName();
    }
}
