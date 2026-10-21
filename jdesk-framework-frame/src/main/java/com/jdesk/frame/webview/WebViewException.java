package com.jdesk.frame.webview;

/**
 * Runtime failure while creating or using a frame-level WebView.
 */
public class WebViewException extends RuntimeException {

    public WebViewException(String message) {
        super(message);
    }

    public WebViewException(String message, Throwable cause) {
        super(message, cause);
    }
}
