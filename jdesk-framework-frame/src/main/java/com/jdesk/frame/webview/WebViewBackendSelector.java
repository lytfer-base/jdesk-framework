package com.jdesk.frame.webview;

/**
 * Chooses the concrete browser backend without loading backend implementations.
 */
final class WebViewBackendSelector {

    private WebViewBackendSelector() {
    }

    static WebViewBackendKind select(WebViewBackendKind requested,
                                     boolean jcefAvailable,
                                     boolean jxBrowserAvailable) {
        WebViewBackendKind effective = requested == null ? WebViewBackendKind.AUTO : requested;
        return switch (effective) {
            case AUTO -> selectAuto(jcefAvailable, jxBrowserAvailable);
            case JCEF -> require(WebViewBackendKind.JCEF, jcefAvailable);
            case JXBROWSER -> require(WebViewBackendKind.JXBROWSER, jxBrowserAvailable);
        };
    }

    private static WebViewBackendKind selectAuto(boolean jcefAvailable, boolean jxBrowserAvailable) {
        if (jcefAvailable) {
            return WebViewBackendKind.JCEF;
        }
        if (jxBrowserAvailable) {
            return WebViewBackendKind.JXBROWSER;
        }
        throw new WebViewException("No WebView backend is available.");
    }

    private static WebViewBackendKind require(WebViewBackendKind backend, boolean available) {
        if (available) {
            return backend;
        }
        throw new WebViewException("Configured WebView backend " + backend + " is not available.");
    }
}
