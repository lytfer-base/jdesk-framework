package com.jdesk.frame.webview.resource.http;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebResourceAuthTest {

    private static final String COOKIE = WebResourceAuth.COOKIE_NAME + "=tok-abc";
    private static final String PUBLIC = "myapp.localhost";
    private static final String PUBLIC_ORIGIN = "http://myapp.localhost";
    private static final String HOST = "127.0.0.1:4321";

    private final WebResourceAuth auth = new WebResourceAuth(PUBLIC, 4321, "tok-abc");

    /** Accepts Chromium's proxied form (public Host, no port) and direct loopback requests such as curl's. */
    @Test
    void acceptsSameOriginRequestWithCookie() {
        assertEquals(Optional.empty(), auth.reject("GET", PUBLIC, COOKIE, null, "same-origin", "cors"));
        assertEquals(Optional.empty(), auth.reject("GET", PUBLIC + ":80", "a=b; " + COOKIE + "; c=d",
                PUBLIC_ORIGIN, "none", "navigate"));
        assertEquals(Optional.empty(), auth.reject("POST", PUBLIC, COOKIE, PUBLIC_ORIGIN, "same-origin", "cors"));
        assertEquals(Optional.empty(), auth.reject("GET", "MyApp.localhost", COOKIE, null, null, null));
        assertEquals(Optional.empty(), auth.reject("GET", HOST, COOKIE, null, "same-origin", "cors"));
        assertEquals(Optional.empty(), auth.reject("POST", HOST, COOKIE, "http://127.0.0.1:4321", "same-origin", "cors"));
    }

    @Test
    void rejectsWrongHostMissingOrWrongCookieAndForeignOrigin() {
        assertTrue(auth.reject("GET", "localhost:4321", COOKIE, null, null, null).isPresent(), "host must match");
        assertTrue(auth.reject("GET", "127.0.0.1:4322", COOKIE, null, null, null).isPresent());
        assertTrue(auth.reject("GET", "evil.localhost", COOKIE, null, null, null).isPresent(), "other .localhost name");
        assertTrue(auth.reject("GET", PUBLIC + ":4321", COOKIE, null, null, null).isPresent(), "public host with port");
        assertTrue(auth.reject("GET", null, COOKIE, null, null, null).isPresent(), "no Host");
        assertTrue(auth.reject("GET", PUBLIC, null, null, null, null).isPresent(), "no cookie");
        assertTrue(auth.reject("GET", PUBLIC, WebResourceAuth.COOKIE_NAME + "=tok-abd", null, null, null).isPresent());
        assertTrue(auth.reject("GET", PUBLIC, COOKIE, "http://evil.example", null, null).isPresent());
        assertTrue(auth.reject("GET", PUBLIC, COOKIE, "http://evil.localhost", null, null).isPresent());
        assertTrue(auth.reject("GET", PUBLIC, COOKIE, "http://127.0.0.1:4322", null, null).isPresent());
        assertTrue(auth.reject("GET", PUBLIC, COOKIE, "null", "cross-site", "no-cors").isPresent(), "Origin: null too");
    }

    /**
     * A sandboxed iframe is an opaque origin; its subresource requests (cookie, no Origin, no-cors, cross-site) are
     * the only cross-site requests allowed.
     */
    @Test
    void crossSiteIsOnlyAllowedForNoCorsReadsWithCookie() {
        assertEquals(Optional.empty(), auth.reject("GET", PUBLIC, COOKIE, null, "cross-site", "no-cors"));
        assertEquals(Optional.empty(), auth.reject("HEAD", PUBLIC, COOKIE, null, "cross-site", "no-cors"));
        assertTrue(auth.reject("GET", PUBLIC, COOKIE, null, "cross-site", "cors").isPresent(), "cross-site cors");
        assertTrue(auth.reject("GET", PUBLIC, COOKIE, null, "cross-site", "navigate").isPresent(), "cross-site navigate");
        assertTrue(auth.reject("GET", PUBLIC, COOKIE, null, "cross-site", null).isPresent(), "cross-site without a mode");
        assertTrue(auth.reject("POST", PUBLIC, COOKIE, null, "cross-site", "no-cors").isPresent(), "cross-site write");
        assertTrue(auth.reject("GET", PUBLIC, COOKIE, null, "same-site", "no-cors").isEmpty(), "same-site too");
        assertTrue(auth.reject("GET", PUBLIC, null, null, "cross-site", "no-cors").isPresent(), "cross-site, no cookie");
    }

    @Test
    void unboundPortRejectsEverything() {
        assertTrue(new WebResourceAuth(PUBLIC, "tok-abc").reject("GET", PUBLIC, COOKIE, null, null, null).isPresent());
        assertTrue(new WebResourceAuth(PUBLIC, "tok-abc").reject("GET", HOST, COOKIE, null, null, null).isPresent());
    }

    /** The public host must be a single-label .localhost name; Chromium trusts no other name as loopback. */
    @Test
    void publicHostMustBeASingleLocalhostLabel() {
        assertThrows(IllegalArgumentException.class, () -> new WebResourceAuth("myapp.app", "t"));
        assertThrows(IllegalArgumentException.class, () -> new WebResourceAuth("localhost", "t"));
        assertThrows(IllegalArgumentException.class, () -> new WebResourceAuth("a.b.localhost", "t"));
        assertThrows(IllegalArgumentException.class, () -> new WebResourceAuth("MyApp.localhost", "t"));
        assertThrows(IllegalArgumentException.class, () -> new WebResourceAuth("-x.localhost", "t"));
        assertThrows(IllegalArgumentException.class, () -> new WebResourceAuth(null, "t"));
        assertEquals(PUBLIC_ORIGIN, new WebResourceAuth("myapp.localhost", "t").publicOrigin());
        assertEquals("my-app2.localhost", new WebResourceAuth("my-app2.localhost", "t").publicHost());
    }

    @Test
    void tokensAreLongRandomAndUrlSafe() {
        String a = WebResourceAuth.newToken();
        String b = WebResourceAuth.newToken();
        assertNotEquals(a, b);
        assertTrue(a.length() >= 43, a);
        assertTrue(a.matches("[A-Za-z0-9_-]+"), a);
        assertEquals("tok-abc", auth.cookieValue());
    }
}
