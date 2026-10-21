package com.jdesk.frame.webview.bridge.impl;

import com.jdesk.frame.webview.bridge.WebBridgeOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WebBridgeBootstrapScriptTest {

    @Test
    void createsPublicBridgeAndReadyEventForJcefEndpoint() {
        String script = new WebBridgeBootstrapScript()
                .create(WebBridgeOptions.defaults(), "window.jdeskQuery");

        assertTrue(script.contains("window.jdeskBridge"));
        assertTrue(script.contains("invoke: function(method, params)"));
        assertTrue(script.contains("on: function(event, handler)"));
        assertTrue(script.contains("off: function(event, handler)"));
        assertTrue(script.contains("window.__jdeskBridgeEmit"));
        assertTrue(script.contains("jdesk-bridge-ready"));
        assertTrue(script.contains("window.jdeskQuery({"));
        assertTrue(script.contains("request: JSON.stringify(request)"));
        assertTrue(script.contains("onSuccess: function(response)"));
        assertTrue(script.contains("onFailure: function(errorCode, errorMessage)"));
    }

    @Test
    void createsPublicBridgeForJxBrowserEndpoint() {
        String script = new WebBridgeBootstrapScript()
                .create(WebBridgeOptions.defaults(), "window.__jdeskBridgeNative.postMessage");

        assertTrue(script.contains("window.__jdeskBridgeReceive"));
        assertTrue(script.contains("window.__jdeskBridgeNative.postMessage(JSON.stringify(request))"));
    }
}
