package com.jdesk.frame.webview.jcef;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the dispose chain: one failing step must not leak the resources after it, especially the CefApp released last.
 */
final class JcefWebViewServiceTest {

    @Test
    void aFailedDisposeStepDoesNotStopLaterSteps() {
        List<String> ran = new ArrayList<>();

        RuntimeException failure = JcefWebViewService.runDisposeStep(null, () -> {
            ran.add("first");
            throw new IllegalStateException("first failed");
        });
        failure = JcefWebViewService.runDisposeStep(failure, () -> ran.add("second"));

        assertEquals(List.of("first", "second"), ran, "later steps must run after a failed one");
        assertEquals("first failed", failure.getMessage());
    }

    @Test
    void laterFailuresAreSuppressedOntoTheFirstOne() {
        RuntimeException failure = JcefWebViewService.runDisposeStep(null, () -> {
            throw new IllegalStateException("first failed");
        });
        failure = JcefWebViewService.runDisposeStep(failure, () -> {
            throw new IllegalStateException("second failed");
        });

        assertEquals("first failed", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("second failed", failure.getSuppressed()[0].getMessage());
    }
}
