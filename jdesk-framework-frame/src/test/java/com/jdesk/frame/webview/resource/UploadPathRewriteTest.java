package com.jdesk.frame.webview.resource;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadPathRewriteTest {

    private static final String BASE = "http://127.0.0.1:4321";
    private static final String HANDOFF = BASE + "/file/record/dropped-files";
    private static final String TOKEN = "handoff-token";

    @Test
    void rewritesFileBackedMultipartIntoUploadFilesFieldAndAppendsTheToken() {
        List<UploadPathRewrite.Segment> segments = List.of(
                new UploadPathRewrite.Segment("note", "hello", null, null),
                new UploadPathRewrite.Segment("dropped", null, "a.txt", "C:\\a.txt"),
                new UploadPathRewrite.Segment("dropped", null, "b.txt", "C:\\b.txt"));

        List<Map.Entry<String, String>> pairs = UploadPathRewrite.rewrite(HANDOFF, HANDOFF, TOKEN, segments)
                .orElseThrow();

        assertEquals("note", pairs.get(0).getKey());
        assertEquals("hello", pairs.get(0).getValue());
        assertEquals(WebResourceUploadForm.FIELD, pairs.get(1).getKey());
        assertTrue(pairs.get(1).getValue().contains("C:\\\\b.txt"));
        assertEquals(Map.entry(WebResourceUploadForm.TOKEN_FIELD, TOKEN), Map.entry(pairs.get(2).getKey(), pairs.get(2).getValue()));
        assertEquals(3, pairs.size());
    }

    /** Only the handoff address is rewritten; other addresses, other hosts, Blob parts and plain forms pass as is. */
    @Test
    void rewritesNothingButTheHandoffAddress() {
        List<UploadPathRewrite.Segment> withPath = List.of(new UploadPathRewrite.Segment("f", null, "a.txt", "C:\\a.txt"));
        assertEquals(Optional.empty(), UploadPathRewrite.rewrite(BASE + "/file/record/images", HANDOFF, TOKEN, withPath),
                "a real upload on the same origin is untouched");
        assertEquals(Optional.empty(), UploadPathRewrite.rewrite(HANDOFF + "/deeper", HANDOFF, TOKEN, withPath), "prefix only");
        assertEquals(Optional.empty(), UploadPathRewrite.rewrite(HANDOFF, "", TOKEN, withPath), "no handoff declared");
        assertEquals(Optional.empty(), UploadPathRewrite.rewrite("http://127.0.0.1:9999/file/x", HANDOFF, TOKEN, withPath));
        assertEquals(Optional.empty(), UploadPathRewrite.rewrite("https://example.com/upload", HANDOFF, TOKEN, withPath));
        assertEquals(Optional.empty(), UploadPathRewrite.rewrite(HANDOFF, HANDOFF, TOKEN, List.of(
                new UploadPathRewrite.Segment("f", null, "a.txt", null))), "a Blob part has no path");
        assertEquals(Optional.empty(), UploadPathRewrite.rewrite(HANDOFF, HANDOFF, TOKEN, List.of(
                new UploadPathRewrite.Segment("f", null, "a.txt", "C:\\a.txt"),
                new UploadPathRewrite.Segment("g", null, "b.txt", ""))), "a mix with a pathless part passes too");
        assertEquals(Optional.empty(), UploadPathRewrite.rewrite(HANDOFF, HANDOFF, TOKEN, List.of(
                new UploadPathRewrite.Segment("only", "text", null, null))), "no file parts, no rewrite");
    }

    @Test
    void withoutATokenNothingIsRewritten() {
        List<UploadPathRewrite.Segment> withPath = List.of(new UploadPathRewrite.Segment("f", null, "a.txt", "C:\\a.txt"));

        assertEquals(Optional.empty(), UploadPathRewrite.rewrite(HANDOFF, HANDOFF, null, withPath));
        assertEquals(Optional.empty(), UploadPathRewrite.rewrite(HANDOFF, HANDOFF, " ", withPath));
    }

    /** Page fields with the reserved names block the rewrite, or they would pass with the token as a real path list. */
    @Test
    void pageFieldsUsingTheReservedNamesBlockTheRewrite() {
        UploadPathRewrite.Segment file = new UploadPathRewrite.Segment("f", null, "a.png", "C:\\a.png");
        String forged = WebResourceUploadForm.encode(List.of(new WebResourceUploadFile("x.png", "C:\\secret.png")));

        assertEquals(Optional.empty(), UploadPathRewrite.rewrite(HANDOFF, HANDOFF, TOKEN, List.of(
                new UploadPathRewrite.Segment(WebResourceUploadForm.FIELD, forged, null, null), file)));
        assertEquals(Optional.empty(), UploadPathRewrite.rewrite(HANDOFF, HANDOFF, TOKEN, List.of(
                file, new UploadPathRewrite.Segment(WebResourceUploadForm.TOKEN_FIELD, "guess", null, null))));
    }
}
