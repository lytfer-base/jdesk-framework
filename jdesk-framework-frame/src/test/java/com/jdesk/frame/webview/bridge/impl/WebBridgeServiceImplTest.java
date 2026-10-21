package com.jdesk.frame.webview.bridge.impl;

import com.jdesk.frame.webview.JdfWebView;
import com.jdesk.frame.webview.WebViewListener;
import com.jdesk.frame.webview.bridge.WebBridgeEvent;
import com.jdesk.frame.webview.bridge.WebBridgeOptions;
import com.jdesk.frame.webview.bridge.WebBridgeSession;
import com.jdesk.frame.webview.bridge.spi.WebBridgeTransport;
import com.jdesk.frame.webview.bridge.spi.WebBridgeTransportContext;
import com.jdesk.frame.webview.bridge.spi.WebBridgeTransportProvider;
import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.JPanel;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebBridgeServiceImplTest {

    @Test
    void attachCreatesTransportSessionAndInjectsBootstrap() {
        FakeBridgeWebView webView = new FakeBridgeWebView();
        WebBridgeServiceImpl service = new WebBridgeServiceImpl();

        WebBridgeSession session = service.attach(webView, WebBridgeOptions.defaults());

        assertNotNull(session);
        assertTrue(webView.transport.attached);
        assertTrue(webView.transport.bootstrapInjected);
        assertSame(webView, webView.transport.context.webView());
    }

    @Test
    void attachRejectsWebViewWithoutBridgeTransport() {
        WebBridgeServiceImpl service = new WebBridgeServiceImpl();

        assertThrows(IllegalArgumentException.class,
                () -> service.attach(new PlainWebView(), WebBridgeOptions.defaults()));
    }

    private static class PlainWebView implements JdfWebView {
        @Override
        public JComponent component() {
            return new JPanel();
        }

        @Override
        public void loadUrl(String url) {
        }

        @Override
        public void loadHtml(String html) {
        }

        @Override
        public void executeJavaScript(String script) {
        }

        @Override
        public String url() {
            return "";
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
    }

    private static final class FakeBridgeWebView extends PlainWebView implements WebBridgeTransportProvider {
        private final RecordingTransport transport = new RecordingTransport();

        @Override
        public WebBridgeTransport createWebBridgeTransport() {
            return transport;
        }
    }

    private static final class RecordingTransport implements WebBridgeTransport {
        private boolean attached;
        private boolean bootstrapInjected;
        private WebBridgeTransportContext context;

        @Override
        public void attach(WebBridgeTransportContext context) {
            attached = true;
            this.context = context;
        }

        @Override
        public void send(com.jdesk.frame.webview.bridge.WebBridgeResponse response) {
        }

        @Override
        public void sendEvent(WebBridgeEvent event) {
        }

        @Override
        public void injectBootstrap() {
            bootstrapInjected = true;
        }

        @Override
        public void dispose() {
        }
    }
}
