package com.jdesk.frame.webview;

/**
 * Navigation baseline of a WebView: locked to the origin of its first content document. Locking is synchronized, and
 * reads rely on volatile fields.
 * Don't count the engine's bootstrap page ({@code about:blank}) as content: locked there, the real page is blocked.
 */
public final class NavigationBaseline {

    private volatile boolean contentLoadRequested;
    private volatile boolean locked;
    private volatile String origin;

    /** Call when the WebView itself starts loadUrl or loadHtml. */
    public void contentLoadRequested() {
        contentLoadRequested = true;
    }

    /** Call when a main-frame document commits; the first content document locks the baseline. */
    public synchronized void documentCommitted(String url) {
        if (locked || !contentLoadRequested || isBootstrap(url)) {
            return;
        }
        lockTo(url);
    }

    /**
     * Call when a main-frame document fails to load; after a content load was requested it locks the baseline, to an
     * opaque origin if the URL is unusable, so the view never stays unguarded. Bootstrap failures don't count.
     */
    public synchronized void documentFailed(String url) {
        if (locked || !contentLoadRequested || isAboutScheme(url)) {
            return;
        }
        lockTo(url);
    }

    /** Returns null while undecided (allow all), an empty string for an opaque origin (block all), or the origin. */
    public String origin() {
        return locked ? origin : null;
    }

    public boolean isLocked() {
        return locked;
    }

    private void lockTo(String url) {
        String value = WebViewNavigationPolicy.originOf(url);
        origin = value == null ? "" : value;
        locked = true;
    }

    private static boolean isBootstrap(String url) {
        return url == null || url.isBlank() || isAboutScheme(url);
    }

    private static boolean isAboutScheme(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String value = url.trim();
        return "about:blank".equalsIgnoreCase(value) || value.regionMatches(true, 0, "about:", 0, 6);
    }
}
