package com.jdesk.frame.webview.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WebBridgeOptionsTest {

    @Test
    void builderNormalizesBlankNamespace() {
        WebBridgeOptions options = WebBridgeOptions.builder()
                .namespace(" ")
                .injectOnLoad(false)
                .build();

        assertEquals("jdeskBridge", options.namespace());
        assertFalse(options.injectOnLoad());
    }
}
