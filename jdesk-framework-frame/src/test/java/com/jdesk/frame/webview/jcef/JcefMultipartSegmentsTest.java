package com.jdesk.frame.webview.jcef;

import com.jdesk.frame.webview.resource.UploadPathRewrite;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Inputs follow what the real engine sends: string fields and part headers arrive as bytes, and each file body is a
 * separate element carrying a disk path.
 */
final class JcefMultipartSegmentsTest {

    private static final String BOUNDARY = "----WebKitFormBoundaryHk08TvoLN4C6lfrX";
    private static final String CONTENT_TYPE = "multipart/form-data; boundary=" + BOUNDARY;
    private static final String DELIMITER = "--" + BOUNDARY;
    private static final String FILE_PATH = "C:\\Users\\me\\AppData\\Local\\Temp\\dropped.txt";

    @Test
    void readsStringFieldsAndTheFileBackedSegment() {
        Optional<List<UploadPathRewrite.Segment>> segments = JcefMultipartSegments.parse(CONTENT_TYPE, List.of(
                JcefMultipartSegments.Element.bytes(DELIMITER + "\r\n"
                        + "Content-Disposition: form-data; name=\"k\"\r\n\r\nv\r\n"
                        + DELIMITER + "\r\n"
                        + "Content-Disposition: form-data; name=\"f\"; filename=\"dropped.txt\"\r\n"
                        + "Content-Type: text/plain\r\n\r\n"),
                JcefMultipartSegments.Element.file(FILE_PATH),
                JcefMultipartSegments.Element.bytes("\r\n" + DELIMITER + "--\r\n")));

        assertEquals(Optional.of(List.of(
                        new UploadPathRewrite.Segment("k", "v", null, null),
                        new UploadPathRewrite.Segment("f", null, "dropped.txt", FILE_PATH))),
                segments);
    }

    @Test
    void readsTwoFilesInOneRequest() {
        Optional<List<UploadPathRewrite.Segment>> segments = JcefMultipartSegments.parse(CONTENT_TYPE, List.of(
                JcefMultipartSegments.Element.bytes(DELIMITER + "\r\n"
                        + "Content-Disposition: form-data; name=\"f\"; filename=\"a.txt\"\r\n\r\n"),
                JcefMultipartSegments.Element.file("C:\\a.txt"),
                JcefMultipartSegments.Element.bytes("\r\n" + DELIMITER + "\r\n"
                        + "Content-Disposition: form-data; name=\"f\"; filename=\"b.txt\"\r\n\r\n"),
                JcefMultipartSegments.Element.file("C:\\b.txt"),
                JcefMultipartSegments.Element.bytes("\r\n" + DELIMITER + "--\r\n")));

        assertEquals(Optional.of(List.of(
                        new UploadPathRewrite.Segment("f", null, "a.txt", "C:\\a.txt"),
                        new UploadPathRewrite.Segment("f", null, "b.txt", "C:\\b.txt"))),
                segments);
    }

    /** An in-memory Blob part has no disk path, so the request isn't rewritten and the server gets real multipart. */
    @Test
    void refusesWhenAFilePartHasNoDiskPath() {
        Optional<List<UploadPathRewrite.Segment>> segments = JcefMultipartSegments.parse(CONTENT_TYPE, List.of(
                JcefMultipartSegments.Element.bytes(DELIMITER + "\r\n"
                        + "Content-Disposition: form-data; name=\"f\"; filename=\"blob\"\r\n\r\n"),
                JcefMultipartSegments.Element.file("   "),
                JcefMultipartSegments.Element.bytes("\r\n" + DELIMITER + "--\r\n")));

        assertTrue(segments.isEmpty());
    }

    @Test
    void refusesWhenTheContentTypeCarriesNoBoundary() {
        assertTrue(JcefMultipartSegments.parse("multipart/form-data", List.of(
                JcefMultipartSegments.Element.bytes("whatever"))).isEmpty());
    }

    /** An unterminated trailing part means an unknown shape, so parsing refuses. */
    @Test
    void refusesWhenTheLastPartIsNotTerminated() {
        Optional<List<UploadPathRewrite.Segment>> segments = JcefMultipartSegments.parse(CONTENT_TYPE, List.of(
                JcefMultipartSegments.Element.bytes(DELIMITER + "\r\n"
                        + "Content-Disposition: form-data; name=\"k\"\r\n\r\n")));

        assertTrue(segments.isEmpty());
    }

    /** A form without files still parses; UploadPathRewrite decides whether to rewrite. */
    @Test
    void readsAPlainFormWithoutFiles() {
        Optional<List<UploadPathRewrite.Segment>> segments = JcefMultipartSegments.parse(CONTENT_TYPE, List.of(
                JcefMultipartSegments.Element.bytes(DELIMITER + "\r\n"
                        + "Content-Disposition: form-data; name=\"k\"\r\n\r\nv\r\n"
                        + DELIMITER + "--\r\n")));

        assertEquals(Optional.of(List.of(new UploadPathRewrite.Segment("k", "v", null, null))), segments);
    }
}
