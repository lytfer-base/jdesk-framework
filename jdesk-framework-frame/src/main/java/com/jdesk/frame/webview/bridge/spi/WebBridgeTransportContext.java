package com.jdesk.frame.webview.bridge.spi;

import com.jdesk.frame.webview.JdfWebView;
import com.jdesk.frame.webview.bridge.WebBridgeOptions;
import com.jdesk.frame.webview.bridge.impl.WebBridgeBootstrapScript;
import com.jdesk.frame.webview.bridge.impl.WebBridgeCodec;

import java.util.Objects;

/** What a transport needs from its session: the WebView, the inbound entry, the options and the protocol tools. */
public final class WebBridgeTransportContext {
    private final JdfWebView webView;
    private final WebBridgeInbound inbound;
    private final WebBridgeOptions options;
    private final WebBridgeCodec codec;
    private final WebBridgeBootstrapScript bootstrapScript;

    /** Creates the context; every argument is required. */
    public WebBridgeTransportContext(JdfWebView webView,
                                     WebBridgeInbound inbound,
                                     WebBridgeOptions options,
                                     WebBridgeCodec codec,
                                     WebBridgeBootstrapScript bootstrapScript) {
        this.webView = Objects.requireNonNull(webView, "webView");
        this.inbound = Objects.requireNonNull(inbound, "inbound");
        this.options = Objects.requireNonNull(options, "options");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.bootstrapScript = Objects.requireNonNull(bootstrapScript, "bootstrapScript");
    }

    public JdfWebView webView() {
        return webView;
    }

    public WebBridgeInbound inbound() {
        return inbound;
    }

    public WebBridgeOptions options() {
        return options;
    }

    public WebBridgeCodec codec() {
        return codec;
    }

    public WebBridgeBootstrapScript bootstrapScript() {
        return bootstrapScript;
    }
}
