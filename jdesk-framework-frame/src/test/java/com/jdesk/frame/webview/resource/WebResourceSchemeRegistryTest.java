package com.jdesk.frame.webview.resource;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WebResourceSchemeRegistryTest {

    @Test
    void dispatchesRegisteredSchemeCaseInsensitively() {
        WebResourceSchemeRegistry registry = new WebResourceSchemeRegistry();
        registry.register("App", request -> CompletableFuture.completedFuture(
                WebResourceResponse.status(204, "")));

        WebResourceResponse response = registry.dispatch(new WebResourceRequest(
                "GET",
                URI.create("app://myapp/records/v2/index.html"),
                null,
                WebResourceType.MAIN_FRAME,
                "")).toCompletableFuture().join();

        assertTrue(registry.hasHandler("app"));
        assertTrue(registry.schemes().contains("app"));
        assertEquals(204, response.statusCode());
    }

    @Test
    void missingSchemeReturnsNotFound() {
        WebResourceSchemeRegistry registry = new WebResourceSchemeRegistry();

        WebResourceResponse response = registry.dispatch(new WebResourceRequest(
                "GET",
                URI.create("app://myapp/missing.html"),
                null,
                WebResourceType.OTHER,
                "")).toCompletableFuture().join();

        assertEquals(404, response.statusCode());
    }

    @Test
    void handlerFailureReturnsServerError() {
        WebResourceSchemeRegistry registry = new WebResourceSchemeRegistry();
        registry.register("app", request -> {
            throw new IllegalStateException("boom");
        });

        WebResourceResponse response = registry.dispatch(new WebResourceRequest(
                "GET",
                URI.create("app://myapp/index.html"),
                null,
                WebResourceType.OTHER,
                "")).toCompletableFuture().join();

        assertEquals(500, response.statusCode());
    }

    @Test
    void unregisterRemovesHandler() {
        WebResourceSchemeRegistry registry = new WebResourceSchemeRegistry();
        registry.register("asset", request -> CompletableFuture.completedFuture(
                WebResourceResponse.status(204, "")));

        registry.unregister("asset");

        assertFalse(registry.hasHandler("asset"));
    }
}
