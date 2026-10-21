package com.jdesk.frame.webview.bridge.spi;

import com.jdesk.core.disposer.Disposable;
import com.jdesk.frame.webview.bridge.WebBridgeEvent;
import com.jdesk.frame.webview.bridge.WebBridgeResponse;

/**
 * WebBridge transport of a browser backend: backends may move messages by entirely different native means, but the
 * session sees only these operations.
 */
public interface WebBridgeTransport extends Disposable {

    /** Binds the session context, which supplies the codec, the options and the inbound dispatcher. */
    void attach(WebBridgeTransportContext context);

    /** Sends a response back to the page. */
    void send(WebBridgeResponse response);

    /** Sends an event to the page. */
    void sendEvent(WebBridgeEvent event);

    /** Injects the bridge script into the page's main frame. */
    void injectBootstrap();
}
