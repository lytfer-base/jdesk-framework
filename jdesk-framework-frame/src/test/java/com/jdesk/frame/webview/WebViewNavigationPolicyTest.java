package com.jdesk.frame.webview;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests navigation decisions: same-origin and allow-listed targets pass and cross-origin ones are refused; an unset
 * baseline allows everything and an opaque one nothing.
 */
final class WebViewNavigationPolicyTest {

    private static final String APP = "http://myapp.localhost";

    @Test
    void sameOriginIsAllowed() {
        WebViewNavigationPolicy policy = WebViewNavigationPolicy.sameOrigin();
        assertTrue(policy.allowsMainFrame(APP + "/app/myapp/projects/v1/index.html", APP));
        assertTrue(policy.allowsMainFrame(APP + "/app/other/page.html#frag", APP));
    }

    @Test
    void crossOriginIsRejected() {
        WebViewNavigationPolicy policy = WebViewNavigationPolicy.sameOrigin();
        assertFalse(policy.allowsMainFrame("https://example.com/", APP));
        // Another port or scheme on the same host is cross-origin.
        assertFalse(policy.allowsMainFrame("http://myapp.localhost:8080/x", APP));
        assertFalse(policy.allowsMainFrame("https://myapp.localhost/x", APP));
    }

    @Test
    void allowedOriginsAreLetThrough() {
        WebViewNavigationPolicy policy =
                WebViewNavigationPolicy.sameOriginPlus(Set.of("https://login.example.com"));
        assertTrue(policy.allowsMainFrame("https://login.example.com/oauth?x=1", APP));
        assertFalse(policy.allowsMainFrame("https://other.example.com/", APP));
    }

    @Test
    void emptyAllowedOriginsBehavesLikeSameOrigin() {
        WebViewNavigationPolicy policy = WebViewNavigationPolicy.sameOriginPlus(Set.of());
        assertTrue(policy.allowsMainFrame(APP + "/x", APP));
        assertFalse(policy.allowsMainFrame("https://example.com/", APP));
    }

    /** An unset baseline means the surface is still loading, so everything passes, its own first page included. */
    @Test
    void unsetBaselineAllowsEverything() {
        WebViewNavigationPolicy policy = WebViewNavigationPolicy.sameOrigin();
        assertTrue(policy.allowsMainFrame(APP + "/x", null));
        assertTrue(policy.allowsMainFrame("https://example.com/", null));
    }

    /** An opaque baseline (fallback HTML) refuses everything; an error page has no reason to navigate. */
    @Test
    void opaqueBaselineRejectsEverything() {
        WebViewNavigationPolicy policy = WebViewNavigationPolicy.sameOriginPlus(Set.of("https://ok.example.com"));
        assertFalse(policy.allowsMainFrame(APP + "/x", ""));
        assertFalse(policy.allowsMainFrame("https://ok.example.com/", ""));
    }

    /** A target without a parsable origin is refused, so malformed URLs can't bypass the check. */
    @Test
    void unparsableTargetIsRejected() {
        WebViewNavigationPolicy policy = WebViewNavigationPolicy.sameOrigin();
        assertFalse(policy.allowsMainFrame("not a url", APP));
        assertFalse(policy.allowsMainFrame("", APP));
        assertFalse(policy.allowsMainFrame(null, APP));
    }

    /** Default ports (http 80, https 443) match their omitted form; other ports don't. */
    @Test
    void defaultPortIsNormalizedAway() {
        assertEquals(WebViewNavigationPolicy.originOf("http://host"), WebViewNavigationPolicy.originOf("http://host:80"));
        assertEquals(WebViewNavigationPolicy.originOf("https://host"), WebViewNavigationPolicy.originOf("https://host:443"));
        assertNotEquals(WebViewNavigationPolicy.originOf("http://host"), WebViewNavigationPolicy.originOf("http://host:8080"));
    }

    /** Scheme and host are case-insensitive and compared in lowercase. */
    @Test
    void caseIsIgnoredWhenMatchingOrigin() {
        WebViewNavigationPolicy policy = WebViewNavigationPolicy.sameOrigin();
        assertTrue(policy.allowsMainFrame("HTTP://MYAPP.LOCALHOST/x", APP));
    }
}
