package com.jdesk.demo;

import com.jdesk.core.disposer.Disposer;
import com.jdesk.frame.webview.JdfWebView;
import com.jdesk.frame.webview.WebViewOptions;
import com.jdesk.frame.webview.WebViewService;

import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.util.function.Supplier;

/**
 * The WebView in the center of a demo panel; reloading replaces it with a fresh view that loads the HTML again.
 * Don't reload with a second {@code loadHtml}: the first page locks the navigation baseline and blocks data URLs.
 */
final class ReloadableWebView {

    private final WebViewService service;
    private final Supplier<String> html;
    private final JPanel host;
    private JdfWebView current;

    /** Creates the first view and adds it to the center of {@code host}; call on the EDT. */
    ReloadableWebView(WebViewService service, Supplier<String> html, JPanel host) {
        this.service = service;
        this.html = html;
        this.host = host;
        this.current = createView();
        host.add(current.component(), BorderLayout.CENTER);
    }

    JdfWebView current() {
        return current;
    }

    /** Swaps in a fresh view and disposes the old one; call on the EDT. */
    void reload() {
        JdfWebView fresh = createView();
        JdfWebView old = current;
        host.remove(old.component());
        host.add(fresh.component(), BorderLayout.CENTER);
        host.revalidate();
        host.repaint();
        current = fresh;
        Disposer.dispose(old);
    }

    void dispose() {
        Disposer.dispose(current);
    }

    private JdfWebView createView() {
        return service.createWebView(WebViewOptions.builder().initialHtml(html.get()).build());
    }
}
