package com.jdesk.frame.webview.bridge;

import com.jdesk.core.i18n.LocalizedException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebBridgeErrorTest {
    @Test
    void localizedFailureCarriesReasonAndParams() {
        LocalizedException failure = new LocalizedException("records.error.saveFailed", Map.of("count", 2), "save failed");
        WebBridgeError error = WebBridgeError.of(WebBridgeErrorCode.INVALID_REQUEST, new CompletionException(failure));
        assertEquals(WebBridgeErrorCode.INVALID_REQUEST, error.code());
        assertEquals("save failed", error.message());
        assertEquals("records.error.saveFailed", error.details().path("reason").asText());
        assertEquals(2, error.details().path("params").path("count").asInt());
    }

    /** Boolean params reach the page as booleans for branching; other non-numeric params stay display text. */
    @Test
    void booleanParamsStayBooleans() {
        LocalizedException failure = new LocalizedException("records.error.saveFailed",
                Map.of("locked", true, "path", "a.md"), "save failed");
        WebBridgeError error = WebBridgeError.of(WebBridgeErrorCode.INVALID_REQUEST, failure);
        assertTrue(error.details().path("params").path("locked").isBoolean());
        assertTrue(error.details().path("params").path("locked").booleanValue());
        assertEquals("a.md", error.details().path("params").path("path").textValue());
    }

    @Test
    void plainFailureHasNoDetails() {
        WebBridgeError error = WebBridgeError.of(WebBridgeErrorCode.HANDLER_FAILED, new IllegalStateException("boom"));
        assertEquals("boom", error.message());
        assertTrue(error.details().isEmpty());
    }

    /** An exception without a message gives an empty message, not its class name, so the page uses its fallback. */
    @Test
    void failureWithoutMessageLeavesMessageEmpty() {
        assertEquals("", WebBridgeError.of(WebBridgeErrorCode.HANDLER_FAILED, new NullPointerException()).message());
        assertEquals("", WebBridgeError.of(WebBridgeErrorCode.HANDLER_FAILED, new IllegalStateException("  ")).message());
    }
}
