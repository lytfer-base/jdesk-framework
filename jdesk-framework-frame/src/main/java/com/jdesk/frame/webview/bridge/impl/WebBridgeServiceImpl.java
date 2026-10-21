package com.jdesk.frame.webview.bridge.impl;

import com.jdesk.frame.webview.JdfWebView;
import com.jdesk.frame.webview.bridge.WebBridgeOptions;
import com.jdesk.frame.webview.bridge.WebBridgeService;
import com.jdesk.frame.webview.bridge.WebBridgeSession;
import com.jdesk.frame.webview.bridge.spi.WebBridgeTransport;
import com.jdesk.frame.webview.bridge.spi.WebBridgeTransportContext;
import com.jdesk.frame.webview.bridge.spi.WebBridgeTransportProvider;

import java.util.Objects;

/**
 * Default {@link WebBridgeService}: wires the backend's transport, the codec and the session dispatcher together;
 * how scripts get injected is up to the transport.
 */
public final class WebBridgeServiceImpl implements WebBridgeService {

    /** Attaches a session to a WebView whose backend provides a bridge transport. */
    @Override
    public WebBridgeSession attach(JdfWebView webView, WebBridgeOptions options) {
        Objects.requireNonNull(webView, "webView");
        WebBridgeOptions actualOptions = options == null ? WebBridgeOptions.defaults() : options;
        if (!(webView instanceof WebBridgeTransportProvider provider)) {
            throw new IllegalArgumentException("WebView backend does not support WebBridge: "
                    + webView.getClass().getName());
        }

        WebBridgeTransport transport = provider.createWebBridgeTransport();
        WebBridgeSessionImpl session = new WebBridgeSessionImpl(transport, actualOptions);
        WebBridgeTransportContext context = new WebBridgeTransportContext(
                webView,
                session::dispatch,
                actualOptions,
                session.codec(),
                session.bootstrapScript());
        transport.attach(context);
        if (actualOptions.injectOnLoad()) {
            transport.injectBootstrap();
        }
        return session;
    }
}
