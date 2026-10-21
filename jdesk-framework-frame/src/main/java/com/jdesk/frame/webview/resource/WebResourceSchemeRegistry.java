package com.jdesk.frame.webview.resource;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Application service that dispatches resource requests to handlers registered by name, the first segment of
 * {@code /<name>/<host>/<path>}.
 */
public final class WebResourceSchemeRegistry {

    private final ConcurrentMap<String, WebResourceHandler> handlers = new ConcurrentHashMap<>();

    public void register(String scheme, WebResourceHandler handler) {
        handlers.put(normalizeScheme(scheme), Objects.requireNonNull(handler, "handler"));
    }

    public void unregister(String scheme) {
        handlers.remove(normalizeScheme(scheme));
    }

    public boolean hasHandler(String scheme) {
        if (scheme == null || scheme.isBlank()) {
            return false;
        }
        return handlers.containsKey(normalizeScheme(scheme));
    }

    public Set<String> schemes() {
        return Set.copyOf(new TreeSet<>(handlers.keySet()));
    }

    public CompletionStage<WebResourceResponse> dispatch(WebResourceRequest request) {
        Objects.requireNonNull(request, "request");
        String scheme = schemeOf(request.uri());
        if (scheme.isEmpty()) {
            return CompletableFuture.completedFuture(WebResourceResponse.status(400, "Resource URI has no scheme."));
        }
        WebResourceHandler handler = handlers.get(scheme);
        if (handler == null) {
            return CompletableFuture.completedFuture(WebResourceResponse.status(404, "No handler for scheme: " + scheme));
        }

        CompletionStage<WebResourceResponse> response;
        try {
            response = handler.handle(request);
        } catch (Throwable failure) {
            return CompletableFuture.completedFuture(WebResourceResponse.status(500, failureMessage(failure)));
        }
        if (response == null) {
            return CompletableFuture.completedFuture(WebResourceResponse.status(500, "Handler returned no response."));
        }
        return response.handle((value, failure) -> {
            if (failure != null) {
                return WebResourceResponse.status(500, failureMessage(failure));
            }
            return value == null
                    ? WebResourceResponse.status(500, "Handler returned no response.")
                    : value;
        });
    }

    private static String schemeOf(URI uri) {
        String scheme = uri.getScheme();
        return scheme == null ? "" : normalizeScheme(scheme);
    }

    private static String normalizeScheme(String scheme) {
        String normalized = Objects.requireNonNull(scheme, "scheme").trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("scheme must not be blank");
        }
        if (!WebResourceRoute.NAME.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid resource name: " + scheme);
        }
        return normalized;
    }

    private static String failureMessage(Throwable failure) {
        Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getName() : message;
    }
}
