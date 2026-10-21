package com.jdesk.frame.webview;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class WebViewServiceApiTest {

    @Test
    void configurableServiceCreatesConcreteBackendLazily() {
        AtomicInteger factories = new AtomicInteger();
        ConfigurableWebViewService service = new ConfigurableWebViewService(
                null,
                () -> WebViewBackendKind.AUTO,
                () -> true,
                () -> true,
                backend -> {
                    factories.incrementAndGet();
                    return new RecordingWebViewService(backend);
                });

        assertEquals(0, factories.get());
        assertEquals(WebViewBackendStatus.READY, service.getBackendStatus());
        assertEquals(1, factories.get());
    }

    private static final class RecordingWebViewService implements WebViewService {
        private final WebViewBackendKind backend;

        private RecordingWebViewService(WebViewBackendKind backend) {
            this.backend = backend;
        }

        @Override
        public JdfWebView createWebView() {
            throw new UnsupportedOperationException();
        }

        @Override
        public JdfWebView createWebView(WebViewOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<JdfWebView> createWebViewAsync(WebViewOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<WebViewWarmupResult> warmUpAsync() {
            return CompletableFuture.completedFuture(WebViewWarmupResult.success(1L, 2L));
        }

        @Override
        public CompletableFuture<WebViewWarmupResult> warmUpViewAsync() {
            return CompletableFuture.completedFuture(WebViewWarmupResult.success(1L, 2L));
        }

        @Override
        public boolean isEngineReady() {
            return true;
        }

        @Override
        public WebViewBackendStatus getBackendStatus() {
            return WebViewBackendStatus.READY;
        }
    }
}
