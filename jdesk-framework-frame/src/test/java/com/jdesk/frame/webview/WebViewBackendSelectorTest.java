package com.jdesk.frame.webview;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class WebViewBackendSelectorTest {

    @Test
    void autoPrefersJcefWhenBothBackendsAreAvailable() {
        assertEquals(WebViewBackendKind.JCEF,
                WebViewBackendSelector.select(WebViewBackendKind.AUTO, true, true));
    }

    @Test
    void autoFallsBackToJxBrowserWhenJcefIsUnavailable() {
        assertEquals(WebViewBackendKind.JXBROWSER,
                WebViewBackendSelector.select(WebViewBackendKind.AUTO, false, true));
    }

    @Test
    void explicitJcefRequiresJcefAvailability() {
        WebViewException error = assertThrows(WebViewException.class,
                () -> WebViewBackendSelector.select(WebViewBackendKind.JCEF, false, true));

        assertEquals("Configured WebView backend JCEF is not available.", error.getMessage());
    }

    @Test
    void explicitJxBrowserRequiresJxBrowserAvailability() {
        WebViewException error = assertThrows(WebViewException.class,
                () -> WebViewBackendSelector.select(WebViewBackendKind.JXBROWSER, true, false));

        assertEquals("Configured WebView backend JXBROWSER is not available.", error.getMessage());
    }

    @Test
    void autoRequiresAtLeastOneAvailableBackend() {
        WebViewException error = assertThrows(WebViewException.class,
                () -> WebViewBackendSelector.select(WebViewBackendKind.AUTO, false, false));

        assertEquals("No WebView backend is available.", error.getMessage());
    }
}
