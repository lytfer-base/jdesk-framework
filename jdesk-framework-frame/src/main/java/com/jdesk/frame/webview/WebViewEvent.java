package com.jdesk.frame.webview;

/**
 * Framework-level event emitted by a WebView without exposing backend classes.
 */
public final class WebViewEvent {

    public enum Type {
        TITLE_CHANGED,
        URL_CHANGED,
        LOAD_FINISHED,
        LOAD_FAILED
    }

    private final JdfWebView webView;
    private final Type type;
    private final String value;

    private WebViewEvent(JdfWebView webView, Type type, String value) {
        this.webView = webView;
        this.type = type;
        this.value = value == null ? "" : value;
    }

    public static WebViewEvent titleChanged(JdfWebView webView, String title) {
        return new WebViewEvent(webView, Type.TITLE_CHANGED, title);
    }

    public static WebViewEvent urlChanged(JdfWebView webView, String url) {
        return new WebViewEvent(webView, Type.URL_CHANGED, url);
    }

    public static WebViewEvent loadFinished(JdfWebView webView, String url) {
        return new WebViewEvent(webView, Type.LOAD_FINISHED, url);
    }

    public static WebViewEvent loadFailed(JdfWebView webView, String failure) {
        return new WebViewEvent(webView, Type.LOAD_FAILED, failure);
    }

    public JdfWebView getWebView() {
        return webView;
    }

    public Type getType() {
        return type;
    }

    public String getValue() {
        return value;
    }
}
