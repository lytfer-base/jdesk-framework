package com.jdesk.frame.webview;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Decides whether a WebView main frame may navigate: only to its baseline's origin or an allowed one. Stateless and
 * engine-free; each backend's view holds its baseline and passes it in.
 */
public final class WebViewNavigationPolicy {

    private static final WebViewNavigationPolicy SAME_ORIGIN = new WebViewNavigationPolicy(Set.of());

    private final Set<String> allowedOrigins;

    private WebViewNavigationPolicy(Set<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    /** Allows only the origin of the view's own document. */
    public static WebViewNavigationPolicy sameOrigin() {
        return SAME_ORIGIN;
    }

    /** Also allows these origins, given as {@code https://host:port}; case and default ports are normalized. */
    public static WebViewNavigationPolicy sameOriginPlus(Set<String> allowedOrigins) {
        Objects.requireNonNull(allowedOrigins, "allowedOrigins");
        if (allowedOrigins.isEmpty()) {
            return SAME_ORIGIN;
        }
        Set<String> normalized = new java.util.LinkedHashSet<>();
        for (String origin : allowedOrigins) {
            String value = originOf(origin);
            if (value != null && !value.isEmpty()) {
                normalized.add(value);
            }
        }
        return new WebViewNavigationPolicy(Set.copyOf(normalized));
    }

    /**
     * Returns whether a main-frame navigation to {@code targetUrl} may proceed.
     *
     * @param baselineOrigin the view's locked baseline; {@code null} while undecided (allow), empty for an opaque
     *                       origin (block)
     */
    public boolean allowsMainFrame(String targetUrl, String baselineOrigin) {
        if (baselineOrigin == null) {
            return true;
        }
        if (baselineOrigin.isEmpty()) {
            return false;
        }
        String target = originOf(targetUrl);
        if (target == null || target.isEmpty()) {
            return false;
        }
        return target.equals(baselineOrigin) || allowedOrigins.contains(target);
    }

    /** Returns {@code scheme://host[:port]} without a default port, or null if unparsable or hostless. */
    public static String originOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (Exception malformed) {
            return null;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) {
            return null;
        }
        String schemeLower = scheme.toLowerCase(Locale.ROOT);
        String origin = schemeLower + "://" + host.toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        return (port < 0 || isDefaultPort(schemeLower, port)) ? origin : origin + ":" + port;
    }

    private static boolean isDefaultPort(String scheme, int port) {
        return ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
    }
}
