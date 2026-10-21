package com.jdesk.demo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class WebViewLevel1CompatibilityPanelTest {

    @Test
    void level1HtmlEscapesBackendName() {
        String html = WebViewLevel1CompatibilityPanel.createLevel1Html("<jcef>");

        assertTrue(html.contains("Backend: &lt;jcef&gt;"));
    }
}
