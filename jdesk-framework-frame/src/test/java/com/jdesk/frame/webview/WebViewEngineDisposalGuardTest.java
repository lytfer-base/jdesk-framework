package com.jdesk.frame.webview;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Architecture rule: the JCEF backend must check for disposal before creating the engine, or an engine created after
 * shutdown won the lock is never closed and Chromium outlives the JVM. The race is too narrow to test, so the source
 * is scanned.
 */
class WebViewEngineDisposalGuardTest {

    private static final Path JCEF_SERVICE =
            Path.of("src/main/java/com/jdesk/frame/webview/jcef/JcefWebViewService.java");

    @Test
    void jcefStartupChecksDisposalBeforeCreatingTheEngine() throws IOException {
        assertChecksDisposal(JCEF_SERVICE, "private void ensureReadyOnBackground(",
                "obtainCefApp(",
                "JCEF must check disposed before creating CefApp: if dispose ran first, nothing was closed "
                        + "and a CefApp created here is orphaned, so Chromium outlives the JVM");
    }

    /** Asserts that {@code disposed} appears between the method signature and the engine creation call. */
    private static void assertChecksDisposal(Path source, String methodSignature, String createCall, String why)
            throws IOException {
        String text = Files.readString(source, StandardCharsets.UTF_8);
        int start = text.indexOf(methodSignature);
        org.junit.jupiter.api.Assertions.assertTrue(start >= 0,
                source + " has no " + methodSignature + "; update this check if the method was renamed");
        int create = text.indexOf(createCall, start);
        org.junit.jupiter.api.Assertions.assertTrue(create > start,
                source + ": " + methodSignature + " has no " + createCall + "; update this check if that call changed");
        org.junit.jupiter.api.Assertions.assertTrue(text.substring(start, create).contains("disposed"), why);
    }
}
