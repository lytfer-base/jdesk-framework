package com.jdesk.frame.webview;

import com.jdesk.core.disposer.Disposable;

import javax.swing.JComponent;

/**
 * Framework-level wrapper around an embedded browser view.
 */
public interface JdfWebView extends Disposable {

    JComponent component();

    void loadUrl(String url);

    void loadHtml(String html);

    /**
     * Runs a script in the page without waiting or returning a value, in call order per view; dropped silently before
     * the page exists. Throws {@link WebViewException} once disposed.
     * Don't add a value-returning overload: JCEF returns nothing, so it would block the EDT on another process.
     */
    void executeJavaScript(String script);

    String url();

    String title();

    boolean isLoading();

    /** Runs a native editing command on the focused element, as its shortcut would; ignored if unsupported. */
    default void executeEditorCommand(JdfEditorCommand command) {
    }

    void addListener(WebViewListener listener);

    void removeListener(WebViewListener listener);
}
