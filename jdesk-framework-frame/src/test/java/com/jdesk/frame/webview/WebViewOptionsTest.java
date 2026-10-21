package com.jdesk.frame.webview;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class WebViewOptionsTest {

    @Test
    void builderRejectsBothInitialUrlAndHtml() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                WebViewOptions.builder()
                        .initialUrl("https://example.test")
                        .initialHtml("<html></html>")
                        .build());

        assertEquals("Only one of initialUrl or initialHtml can be configured.", error.getMessage());
    }

    @Test
    void defaultsToSameOriginNavigationPolicy() {
        assertSame(WebViewNavigationPolicy.sameOrigin(), WebViewOptions.defaults().getNavigationPolicy());
    }

    @Test
    void navigationPolicyCanBeOverridden() {
        WebViewNavigationPolicy policy = WebViewNavigationPolicy.sameOriginPlus(java.util.Set.of("https://ok.example.com"));
        assertSame(policy, WebViewOptions.builder().navigationPolicy(policy).build().getNavigationPolicy());
    }

    @Test
    void navigationPolicyRejectsNull() {
        assertThrows(NullPointerException.class, () -> WebViewOptions.builder().navigationPolicy(null));
    }
}
