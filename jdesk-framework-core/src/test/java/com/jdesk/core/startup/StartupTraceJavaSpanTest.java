package com.jdesk.core.startup;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StartupTraceJavaSpanTest {

    @Test
    void runSpanRecordsSuccessAndReturnsValue() throws Exception {
        StartupTrace trace = StartupTrace.report();

        String result = trace.runSpan("java.span", span -> {
            span.detail("api", "java");
            return "done";
        });

        assertEquals("done", result);
        StartupEvent event = trace.getEvents().get(0);
        assertEquals("java.span", event.getName());
        assertEquals("OK", event.getStatus());
        assertEquals("java", event.getDetails().get("api"));
    }

    @Test
    void runSpanRecordsFailureAndRethrows() {
        StartupTrace trace = StartupTrace.report();

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                trace.runSpan("java.failure", span -> {
                    throw new IllegalStateException("boom");
                })
        );

        assertEquals("boom", failure.getMessage());
        StartupEvent event = trace.getEvents().get(0);
        assertEquals("java.failure", event.getName());
        assertEquals("ERROR", event.getStatus());
        assertEquals("java.lang.IllegalStateException: boom", event.getError());
    }
}
