package com.jdesk.frame.webview;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Architecture rule: the JCEF bridge entry must accept only the main frame; otherwise an untrusted page in a
 * sandboxed iframe could call every registered bridge method. Functional tests can't see this, so the source is
 * scanned.
 */
class WebBridgeMainFrameGuardTest {

    private static final Path JCEF_SERVICE =
            Path.of("src/main/java/com/jdesk/frame/webview/jcef/JcefWebViewService.java");

    @Test
    void jcefBridgeEntryChecksMainFrame() throws IOException {
        assertGuarded(JCEF_SERVICE, "public boolean onQuery(",
                "JCEF onQuery must check frame.isMain(): CefMessageRouter registers jdeskQuery in every V8 context, "
                        + "so without it subframes, sandboxed iframes included, reach every bridge method");
    }

    private static void assertGuarded(Path source, String methodSignature, String why) throws IOException {
        String text = Files.readString(source, StandardCharsets.UTF_8);
        int start = text.indexOf(methodSignature);
        assertTrue(start >= 0, source + " has no " + methodSignature + "; update this check if the method was renamed");
        String body = text.substring(start, Math.min(text.length(), start + 900));
        assertTrue(body.contains("isMain()"), why);
    }
}
