package com.jdesk.frame.webview;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WebViewRuntimeConfigTest {

    @Test
    void webViewBackendDefaultsToAuto() {
        assertEquals(WebViewBackendKind.AUTO,
                WebViewRuntimeConfig.resolveBackend(Map.of(), Map.of()));
    }

    @Test
    void webViewBackendUsesSystemPropertyBeforeEnvironment() {
        assertEquals(WebViewBackendKind.JCEF,
                WebViewRuntimeConfig.resolveBackend(
                        Map.of("jdesk.webview.backend", "jcef"),
                        Map.of("JDESK_WEBVIEW_BACKEND", "jxbrowser")));
    }

    @Test
    void unknownWebViewBackendFallsBackToAuto() {
        assertEquals(WebViewBackendKind.AUTO,
                WebViewRuntimeConfig.resolveBackend(
                        Map.of("jdesk.webview.backend", "unknown"),
                        Map.of()));
    }

    /** Off unless configured: the port has no authentication and must never open by default. */
    @Test
    void remoteDebuggingPortIsAbsentByDefault() {
        assertTrue(WebViewRuntimeConfig.resolveRemoteDebuggingPort(Map.of(), Map.of()).isEmpty());
    }

    @Test
    void remoteDebuggingPortUsesSystemPropertyBeforeEnvironment() {
        assertEquals(9333,
                WebViewRuntimeConfig.resolveRemoteDebuggingPort(
                        Map.of("jdesk.webview.remoteDebuggingPort", "9333"),
                        Map.of("JDESK_WEBVIEW_REMOTE_DEBUGGING_PORT", "9444")).orElseThrow());
    }

    @Test
    void remoteDebuggingPortFallsBackToEnvironment() {
        assertEquals(9444,
                WebViewRuntimeConfig.resolveRemoteDebuggingPort(
                        Map.of(),
                        Map.of("JDESK_WEBVIEW_REMOTE_DEBUGGING_PORT", "9444")).orElseThrow());
    }

    /** A malformed value counts as unset; a diagnostic switch must not stop the app from starting. */
    @Test
    void malformedOrOutOfRangeRemoteDebuggingPortIsIgnored() {
        assertTrue(WebViewRuntimeConfig.resolveRemoteDebuggingPort(
                Map.of("jdesk.webview.remoteDebuggingPort", "abc"), Map.of()).isEmpty());
        assertTrue(WebViewRuntimeConfig.resolveRemoteDebuggingPort(
                Map.of("jdesk.webview.remoteDebuggingPort", "0"), Map.of()).isEmpty());
        assertTrue(WebViewRuntimeConfig.resolveRemoteDebuggingPort(
                Map.of("jdesk.webview.remoteDebuggingPort", "70000"), Map.of()).isEmpty());
        assertTrue(WebViewRuntimeConfig.resolveRemoteDebuggingPort(
                Map.of("jdesk.webview.remoteDebuggingPort", "  "), Map.of()).isEmpty());
    }
}
