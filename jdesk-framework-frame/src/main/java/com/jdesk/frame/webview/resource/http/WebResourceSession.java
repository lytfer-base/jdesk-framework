package com.jdesk.frame.webview.resource.http;

/**
 * Session details for WebView backends: the cookie, the public page origin, the loopback address actually listened
 * on, Chromium rules connecting the two, the path hand-off address for upload rewriting (empty if none) and its
 * token. Application code shouldn't use it.
 * Keep the hand-off token inside Java: never give it to pages or write it to discovery files or logs.
 */
public record WebResourceSession(String cookieName, String cookieDomain, String cookieValue,
                                 String publicBaseUrl, String bindBaseUrl, String pathHandoffUrl,
                                 String uploadToken) {

    /** Chromium proxy rules sending plain http to the loopback port; set {@link #proxyBypassRules()} with them. */
    public String proxyRules() {
        return "http=" + bindBaseUrl.substring("http://".length());
    }

    /**
     * Bypass rules: {@code <-loopback>} drops Chromium's implicit direct route for localhost names, which would keep
     * {@code *.localhost} off the proxy; plain localhost and 127.0.0.1 then go direct again for development.
     */
    public String proxyBypassRules() {
        return "<-loopback>;localhost;127.0.0.1";
    }

    /** Chromium host resolver rules mapping just the public host and port to loopback; other hosts are unaffected. */
    public String hostResolverRules() {
        return "MAP " + hostAndPort(publicBaseUrl) + " " + hostAndPort(bindBaseUrl);
    }

    /** Leaves out the cookie value and the hand-off token. */
    @Override
    public String toString() {
        return "WebResourceSession[publicBaseUrl=" + publicBaseUrl + ", bindBaseUrl=" + bindBaseUrl
                + ", pathHandoffUrl=" + pathHandoffUrl + "]";
    }

    private static String hostAndPort(String baseUrl) {
        return baseUrl.substring("http://".length());
    }
}
