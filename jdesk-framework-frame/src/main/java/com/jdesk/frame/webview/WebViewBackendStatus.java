package com.jdesk.frame.webview;

/**
 * Current state of the WebView backend implementation.
 */
public enum WebViewBackendStatus {
    NOT_STARTED,
    WARMING_UP,
    READY,
    FAILED,
    DISPOSED
}
