package com.jdesk.frame.webview.bridge;

import com.jdesk.frame.webview.JdfWebView;

/** Entry point of WebBridge: attaches a session between Java and the page to an existing {@link JdfWebView}. */
public interface WebBridgeService {

    /** Installs the bridge on {@code webView} and returns the session where application code registers handlers. */
    WebBridgeSession attach(JdfWebView webView, WebBridgeOptions options);
}
