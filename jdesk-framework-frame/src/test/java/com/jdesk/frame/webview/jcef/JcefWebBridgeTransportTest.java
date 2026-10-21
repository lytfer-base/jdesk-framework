package com.jdesk.frame.webview.jcef;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.jdesk.frame.webview.JdfWebView;
import com.jdesk.frame.webview.WebViewListener;
import com.jdesk.frame.webview.bridge.WebBridgeEvent;
import com.jdesk.frame.webview.bridge.WebBridgeOptions;
import com.jdesk.frame.webview.bridge.WebBridgeResponse;
import com.jdesk.frame.webview.bridge.impl.WebBridgeBootstrapScript;
import com.jdesk.frame.webview.bridge.impl.WebBridgeCodec;
import com.jdesk.frame.webview.bridge.spi.WebBridgeInbound;
import com.jdesk.frame.webview.bridge.spi.WebBridgeTransportContext;
import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JcefWebBridgeTransportTest {

    @Test
    void sendEventExecutesFrontendEmitWhenBridgeIsReady() {
        FakeBridgeWebView view = new FakeBridgeWebView();
        JcefWebBridgeTransport transport = new JcefWebBridgeTransport(view);
        transport.attach(new WebBridgeTransportContext(
                view,
                request -> CompletableFuture.failedFuture(new IllegalStateException("not used")),
                WebBridgeOptions.defaults(),
                new WebBridgeCodec(),
                new WebBridgeBootstrapScript()));

        transport.sendEvent(new WebBridgeEvent("records.changed",
                JsonNodeFactory.instance.objectNode().put("count", 3)));

        String script = view.scripts.get(0);
        assertTrue(script.contains("if (window.__jdeskBridgeEmit)"));
        assertTrue(script.contains("window.__jdeskBridgeEmit({\"event\":\"records.changed\""));
    }

    /** receive runs on the CEF UI thread shared by all JCEF windows, so a slow handler must not hold it. */
    @Test
    void aSlowHandlerDoesNotHoldTheCallingThread() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        FakeBridgeWebView view = new FakeBridgeWebView();
        JcefWebBridgeTransport transport = attached(view, request -> {
            awaitQuietly(release);
            return CompletableFuture.completedFuture(WebBridgeResponse.success(request.id(), null));
        });
        BlockingQueue<String> responses = new LinkedBlockingQueue<>();

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> transport.receive(request("1", "slow"), responses::add),
                "receive waited for the handler to finish");
        assertNull(responses.poll(200, TimeUnit.MILLISECONDS), "no response before the handler is released");
        release.countDown();

        String response = responses.poll(5, TimeUnit.SECONDS);
        assertNotNull(response);
        assertTrue(response.contains("\"id\":\"1\""));
        transport.dispose();
    }

    /** Requests from one view reach the handler one at a time in arrival order, like a per-page render thread. */
    @Test
    void requestsFromOneViewAreHandledInArrivalOrder() throws Exception {
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        List<String> finished = Collections.synchronizedList(new ArrayList<>());
        FakeBridgeWebView view = new FakeBridgeWebView();
        JcefWebBridgeTransport transport = attached(view, request -> {
            if (request.method().equals("first")) {
                awaitQuietly(releaseFirst);
            } else {
                secondStarted.countDown();
            }
            finished.add(request.method());
            return CompletableFuture.completedFuture(WebBridgeResponse.success(request.id(), null));
        });
        BlockingQueue<String> responses = new LinkedBlockingQueue<>();

        transport.receive(request("1", "first"), responses::add);
        transport.receive(request("2", "second"), responses::add);
        transport.receive(request("3", "third"), responses::add);
        secondStarted.await(500, TimeUnit.MILLISECONDS);
        releaseFirst.countDown();
        for (int i = 0; i < 3; i++) {
            assertNotNull(responses.poll(5, TimeUnit.SECONDS));
        }

        assertEquals(List.of("first", "second", "third"), finished, "a later request ran before the first finished");
        transport.dispose();
    }

    private static JcefWebBridgeTransport attached(FakeBridgeWebView view, WebBridgeInbound inbound) {
        JcefWebBridgeTransport transport = new JcefWebBridgeTransport(view);
        transport.attach(new WebBridgeTransportContext(view, inbound, WebBridgeOptions.defaults(),
                new WebBridgeCodec(), new WebBridgeBootstrapScript()));
        return transport;
    }

    private static String request(String id, String method) {
        return "{\"id\":\"" + id + "\",\"method\":\"" + method + "\",\"params\":{}}";
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class FakeBridgeWebView implements JdfWebView, JcefBridgeWebView {
        private final JComponent component = new JPanel();
        private final List<String> scripts = new ArrayList<>();

        @Override
        public JComponent component() {
            return component;
        }

        @Override
        public void loadUrl(String url) {
        }

        @Override
        public void loadHtml(String html) {
        }

        @Override
        public void executeJavaScript(String script) {
            scripts.add(script);
        }

        @Override
        public String url() {
            return "http://localhost:5173";
        }

        @Override
        public String title() {
            return "";
        }

        @Override
        public boolean isLoading() {
            return false;
        }

        @Override
        public void addListener(WebViewListener listener) {
        }

        @Override
        public void removeListener(WebViewListener listener) {
        }

        @Override
        public void dispose() {
        }

        @Override
        public void attachBridgeTransport(JcefWebBridgeTransport transport) {
        }

        @Override
        public void detachBridgeTransport(JcefWebBridgeTransport transport) {
        }

        @Override
        public boolean handleBridgeQuery(String request, Consumer<String> responseConsumer) {
            return false;
        }

        @Override
        public void executeBridgeJavaScript(String script) {
            scripts.add(script);
        }

        @Override
        public String bridgeUrl() {
            return url();
        }
    }
}
