package com.jdesk.frame.webview.jcef;

import com.jdesk.frame.webview.JdfEditorCommand;
import com.jdesk.frame.webview.JdfWebView;
import com.jdesk.frame.webview.NavigationBaseline;
import com.jdesk.frame.webview.WebViewEvent;
import com.jdesk.frame.webview.WebViewException;
import com.jdesk.frame.webview.WebViewListener;
import com.jdesk.frame.webview.WebViewNavigationPolicy;
import com.jdesk.frame.webview.bridge.spi.WebBridgeTransport;
import com.jdesk.frame.webview.bridge.spi.WebBridgeTransportProvider;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefDevToolsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Framework view of one JCEF browser: Swing host, event forwarding and WebBridge access, without exposing
 * {@code org.cef} types. The native browser accepts nothing before {@code onAfterCreated}: navigation waits in
 * {@code pendingUrl} and scripts are dropped.
 */
final class JcefWebView implements JdfWebView, WebBridgeTransportProvider, JcefBridgeWebView {

    private static final Logger LOG = LoggerFactory.getLogger(JcefWebView.class);

    private final CefBrowser browser;
    private final JComponent component;
    private final Consumer<JcefWebView> disposeListener;
    private final List<WebViewListener> listeners = new CopyOnWriteArrayList<>();

    private volatile String title = "";
    private volatile String pendingUrl;
    private volatile JcefWebBridgeTransport bridgeTransport;
    private volatile CefDevToolsClient devTools;
    private volatile boolean nativeCreated;
    private volatile boolean disposed;

    private final NavigationBaseline navigationBaseline = new NavigationBaseline();
    private volatile WebViewNavigationPolicy navigationPolicy = WebViewNavigationPolicy.sameOrigin();

    JcefWebView(CefBrowser browser, Component browserComponent, Consumer<JcefWebView> disposeListener) {
        this.browser = Objects.requireNonNull(browser, "browser");
        this.disposeListener = Objects.requireNonNull(disposeListener, "disposeListener");
        this.component = asJComponent(browserComponent);
    }

    @Override
    public JComponent component() {
        return component;
    }

    @Override
    public void loadUrl(String url) {
        navigationBaseline.contentLoadRequested();
        checkNotDisposed();
        if (!nativeCreated) {
            pendingUrl = url;
            return;
        }
        browser.loadURL(url);
    }

    /** Works only before the navigation baseline locks: a data URL has no origin, so it would be blocked afterwards. */
    @Override
    public void loadHtml(String html) {
        navigationBaseline.contentLoadRequested();
        loadUrl(dataUrl(html));
    }

    /** Drops the script without throwing before the native browser exists, since there is no page yet. */
    @Override
    public void executeJavaScript(String script) {
        checkNotDisposed();
        if (!nativeCreated) {
            LOG.debug("jcef.executeJavaScript.dropped reason=nativeBrowserNotCreated");
            return;
        }
        browser.executeJavaScript(script, url(), 0);
    }

    @Override
    public void executeEditorCommand(JdfEditorCommand command) {
        checkNotDisposed();
        JcefEditorCommands.execute(browser, devTools(), Objects.requireNonNull(command, "command"));
    }

    @Override
    public String url() {
        checkNotDisposed();
        String pending = pendingUrl;
        if (!nativeCreated && pending != null) {
            return pending;
        }
        String current = browser.getURL();
        return current == null ? "" : current;
    }

    @Override
    public String title() {
        checkNotDisposed();
        return title;
    }

    @Override
    public boolean isLoading() {
        checkNotDisposed();
        return nativeCreated && browser.isLoading();
    }

    @Override
    public void addListener(WebViewListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public void removeListener(WebViewListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    @Override
    public WebBridgeTransport createWebBridgeTransport() {
        return new JcefWebBridgeTransport(this);
    }

    @Override
    public void attachBridgeTransport(JcefWebBridgeTransport transport) {
        bridgeTransport = transport;
    }

    @Override
    public void detachBridgeTransport(JcefWebBridgeTransport transport) {
        if (bridgeTransport == transport) {
            bridgeTransport = null;
        }
    }

    @Override
    public boolean handleBridgeQuery(String request, Consumer<String> responseConsumer) {
        JcefWebBridgeTransport transport = bridgeTransport;
        return transport != null && transport.receive(request, responseConsumer);
    }

    @Override
    public void executeBridgeJavaScript(String script) {
        executeJavaScript(script);
    }

    @Override
    public String bridgeUrl() {
        return url();
    }

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        try {
            CefDevToolsClient client = devTools;
            devTools = null;
            if (client != null) {
                client.close();
            }
            browser.close(true);
        } finally {
            disposeListener.accept(this);
        }
    }

    CefBrowser browser() {
        return browser;
    }

    NavigationBaseline navigationBaseline() {
        return navigationBaseline;
    }

    WebViewNavigationPolicy navigationPolicy() {
        return navigationPolicy;
    }

    void navigationPolicy(WebViewNavigationPolicy policy) {
        this.navigationPolicy = java.util.Objects.requireNonNull(policy, "policy");
    }

    void fire(WebViewEvent event) {
        for (WebViewListener listener : listeners) {
            listener.onWebViewEvent(event);
        }
    }

    void updateTitle(String newTitle) {
        this.title = newTitle == null ? "" : newTitle;
    }

    /** Marks the native browser ready and loads the navigation that waited for it. */
    void nativeCreated() {
        nativeCreated = true;
        String urlToLoad = pendingUrl;
        pendingUrl = null;
        if (urlToLoad != null && !disposed) {
            browser.loadURL(urlToLoad);
        }
    }

    /** Returns the view's cached DevTools client, an in-process channel that needs no remote debugging port. */
    private CefDevToolsClient devTools() {
        CefDevToolsClient client = devTools;
        if (client == null || client.isClosed()) {
            client = browser.getDevToolsClient();
            devTools = client;
        }
        return client;
    }

    private void checkNotDisposed() {
        if (disposed) {
            throw new WebViewException("WebView is already disposed.");
        }
    }

    /** Wraps HTML in a data URL, since JCEF has no loadHtml. */
    private static String dataUrl(String html) {
        String encoded = java.util.Base64.getEncoder().encodeToString(
                (html == null ? "" : html).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return "data:text/html;charset=UTF-8;base64," + encoded;
    }

    private static JComponent asJComponent(Component browserComponent) {
        if (browserComponent instanceof JComponent jComponent) {
            return jComponent;
        }
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(browserComponent, BorderLayout.CENTER);
        return panel;
    }
}
