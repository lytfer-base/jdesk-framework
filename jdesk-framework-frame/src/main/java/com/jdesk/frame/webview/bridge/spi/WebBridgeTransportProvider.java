package com.jdesk.frame.webview.bridge.spi;

/** Implemented by WebViews whose backend supports WebBridge, so the service can create a backend-specific transport. */
public interface WebBridgeTransportProvider {

    /** Creates a new bridge transport for this view. */
    WebBridgeTransport createWebBridgeTransport();
}
