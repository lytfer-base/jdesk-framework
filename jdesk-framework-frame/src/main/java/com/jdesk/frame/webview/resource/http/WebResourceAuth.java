package com.jdesk.frame.webview.resource.http;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Request authentication for the resource server: Host must be the public host or the loopback bind address, the
 * session cookie is compared in constant time, Origin must be our own and Sec-Fetch-Site same-origin or none, except
 * for no-cors GET or HEAD subresources of a sandboxed iframe without Origin, which still need the cookie. Everything
 * is rejected until {@link #bindPort}. Keep the token in memory: never log it or put it in a URL.
 */
public final class WebResourceAuth {

    public static final String COOKIE_NAME = "jdesk-resource-session";
    public static final String BIND_HOST = "127.0.0.1";

    /** A single-label {@code <label>.localhost}: Chromium resolves it to loopback itself and treats it as trusted. */
    static final Pattern PUBLIC_HOST = Pattern.compile("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.localhost");

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String publicHost;
    private final String token;
    private final byte[] tokenBytes;
    private volatile String bindHost = "";

    public WebResourceAuth(String publicHost, String token) {
        this.publicHost = requirePublicHost(publicHost);
        this.token = Objects.requireNonNull(token, "token");
        this.tokenBytes = token.getBytes(StandardCharsets.UTF_8);
    }

    public WebResourceAuth(String publicHost, int port, String token) {
        this(publicHost, token);
        bindPort(port);
    }

    /** Returns {@code host} if it is a valid public host; otherwise throws {@link IllegalArgumentException}. */
    public static String requirePublicHost(String host) {
        if (host == null || !PUBLIC_HOST.matcher(host).matches()) {
            throw new IllegalArgumentException("Public host must be <label>.localhost, got: " + host);
        }
        return host;
    }

    /** Returns 32 random bytes as unpadded base64url. */
    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public void bindPort(int port) {
        this.bindHost = BIND_HOST + ":" + port;
    }

    public String publicHost() {
        return publicHost;
    }

    public String publicOrigin() {
        return "http://" + publicHost;
    }

    public String cookieValue() {
        return token;
    }

    /** Returns empty to allow the request, or the rejection reason, which is for logs only. */
    public Optional<String> reject(String method, String host, String cookieHeader, String origin,
                                   String secFetchSite, String secFetchMode) {
        if (bindHost.isEmpty() || !hostAllowed(host)) {
            return Optional.of("host");
        }
        if (!cookieMatches(cookieHeader)) {
            return Optional.of("cookie");
        }
        if (origin != null && !originAllowed(origin.trim())) {
            return Optional.of("origin");
        }
        if (secFetchSite != null) {
            String site = secFetchSite.trim().toLowerCase(Locale.ROOT);
            if (!"same-origin".equals(site) && !"none".equals(site) && !sandboxedRead(method, origin, secFetchMode)) {
                return Optional.of("sec-fetch-site");
            }
        }
        return Optional.empty();
    }

    /** Allows the public host, bare or with :80, or the loopback bind address that tools and dev proxies use. */
    private boolean hostAllowed(String host) {
        if (host == null) {
            return false;
        }
        String value = host.trim().toLowerCase(Locale.ROOT);
        return value.equals(publicHost) || value.equals(publicHost + ":80") || value.equals(bindHost);
    }

    private boolean originAllowed(String origin) {
        return origin.equals(publicOrigin()) || origin.equals("http://" + bindHost);
    }

    private static boolean sandboxedRead(String method, String origin, String secFetchMode) {
        boolean read = "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
        return read && origin == null && secFetchMode != null
                && "no-cors".equals(secFetchMode.trim().toLowerCase(Locale.ROOT));
    }

    private boolean cookieMatches(String cookieHeader) {
        if (cookieHeader == null) {
            return false;
        }
        for (String pair : cookieHeader.split(";")) {
            int eq = pair.indexOf('=');
            if (eq < 0 || !COOKIE_NAME.equals(pair.substring(0, eq).trim())) {
                continue;
            }
            byte[] candidate = pair.substring(eq + 1).trim().getBytes(StandardCharsets.UTF_8);
            if (MessageDigest.isEqual(candidate, tokenBytes)) {
                return true;
            }
        }
        return false;
    }
}
