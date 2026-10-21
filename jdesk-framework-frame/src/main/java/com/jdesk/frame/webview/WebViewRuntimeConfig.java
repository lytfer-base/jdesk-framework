package com.jdesk.frame.webview;

import com.jdesk.core.runtime.RuntimeParamRegistry;
import com.jdesk.frame.runtime.params.FrameRuntimeParams;

import java.util.Map;
import java.util.Optional;

/**
 * Resolves the WebView service's runtime settings, the backend and the remote debugging port, from JVM properties
 * and environment variables. A backend may read settings of its own the same way.
 */
public final class WebViewRuntimeConfig {

    public static final String WEBVIEW_BACKEND_PROPERTY = "jdesk.webview.backend";
    public static final String WEBVIEW_BACKEND_ENV = "JDESK_WEBVIEW_BACKEND";
    public static final String REMOTE_DEBUGGING_PORT_PROPERTY = "jdesk.webview.remoteDebuggingPort";
    public static final String REMOTE_DEBUGGING_PORT_ENV = "JDESK_WEBVIEW_REMOTE_DEBUGGING_PORT";

    private WebViewRuntimeConfig() {
    }

    /**
     * Returns the configured Chromium remote debugging port, or empty to keep it closed; Chrome can then inspect the
     * WebView at {@code http://127.0.0.1:<port>}.
     * Enable it only while debugging: the port has no authentication, so any local process can control the page.
     */
    public static Optional<Integer> resolveRemoteDebuggingPort() {
        return resolveRemoteDebuggingPort(systemProperties(), System.getenv());
    }

    public static Optional<Integer> resolveRemoteDebuggingPort(Map<String, String> properties,
                                                               Map<String, String> environment) {
        return firstPresent(properties.get(REMOTE_DEBUGGING_PORT_PROPERTY),
                environment.get(REMOTE_DEBUGGING_PORT_ENV))
                .flatMap(WebViewRuntimeConfig::parsePort);
    }

    /** Treats a non-numeric or out-of-range value as unset, so a typo can't stop the application from starting. */
    private static Optional<Integer> parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            return port > 0 && port <= 65535 ? Optional.of(port) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public static WebViewBackendKind resolveBackend() {
        return firstPresent(systemProperties().get(WEBVIEW_BACKEND_PROPERTY),
                System.getenv().get(WEBVIEW_BACKEND_ENV))
                .or(() -> firstPresent(RuntimeParamRegistry.getString(FrameRuntimeParams.WEBVIEW_BACKEND, null), null))
                .map(WebViewRuntimeConfig::parseBackend)
                .orElse(WebViewBackendKind.AUTO);
    }

    public static WebViewBackendKind resolveBackend(Map<String, String> properties,
                                                    Map<String, String> environment) {
        return firstPresent(properties.get(WEBVIEW_BACKEND_PROPERTY),
                environment.get(WEBVIEW_BACKEND_ENV))
                .map(WebViewRuntimeConfig::parseBackend)
                .orElse(WebViewBackendKind.AUTO);
    }

    private static Optional<String> firstPresent(String first, String second) {
        if (isPresent(first)) {
            return Optional.of(first.trim());
        }
        if (isPresent(second)) {
            return Optional.of(second.trim());
        }
        return Optional.empty();
    }

    private static boolean isPresent(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static WebViewBackendKind parseBackend(String value) {
        for (WebViewBackendKind backend : WebViewBackendKind.values()) {
            if (backend.name().equalsIgnoreCase(value)) {
                return backend;
            }
        }
        return WebViewBackendKind.AUTO;
    }

    private static Map<String, String> systemProperties() {
        return System.getProperties().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        entry -> String.valueOf(entry.getKey()),
                        entry -> String.valueOf(entry.getValue()),
                        (left, right) -> right));
    }
}
