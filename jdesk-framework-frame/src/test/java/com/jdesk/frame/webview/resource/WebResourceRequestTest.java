package com.jdesk.frame.webview.resource;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebResourceRequestTest {

    private static WebResourceRequest withFiles(List<WebResourceUploadFile> files) {
        return new WebResourceRequest("POST", URI.create("file://record/dropped-files"),
                Map.of(), WebResourceType.XHR, "", new byte[0], files);
    }

    @Test
    void oldArityConstructorStillWorksAndCarriesNoUploadFiles() {
        WebResourceRequest request = new WebResourceRequest("POST", URI.create("file://record/images"),
                Map.of(), WebResourceType.XHR, "", new byte[] {1, 2});

        assertEquals(List.of(), request.uploadFiles(), "callers without file parts must get an empty list, not null");
    }

    @Test
    void bodyOnlyConstructorAlsoCarriesNoUploadFiles() {
        WebResourceRequest request = new WebResourceRequest("GET", URI.create("file://record/images/a.png"),
                Map.of(), WebResourceType.IMAGE, "");

        assertEquals(List.of(), request.uploadFiles());
    }

    @Test
    void uploadFilesRoundTrip() {
        WebResourceRequest request = withFiles(List.of(
                new WebResourceUploadFile("a.md", "D:\\notes\\a.md"),
                new WebResourceUploadFile("b.txt", "D:\\notes\\b.txt")));

        assertEquals(List.of("a.md", "b.txt"), request.uploadFiles().stream()
                .map(WebResourceUploadFile::name).toList());
        assertEquals(List.of("D:\\notes\\a.md", "D:\\notes\\b.txt"), request.uploadFiles().stream()
                .map(WebResourceUploadFile::path).toList());
    }

    @Test
    void uploadFilesAreImmutableAndDetachedFromTheCallerList() {
        List<WebResourceUploadFile> source = new ArrayList<>();
        source.add(new WebResourceUploadFile("a.md", "D:\\notes\\a.md"));
        WebResourceRequest request = withFiles(source);

        source.clear();

        assertEquals(1, request.uploadFiles().size(), "the request must copy the caller's list");
        assertThrows(UnsupportedOperationException.class,
                () -> request.uploadFiles().add(new WebResourceUploadFile("c.md", "D:\\c.md")));
    }

    @Test
    void nullUploadFilesBecomesEmpty() {
        WebResourceRequest request = withFiles(null);

        assertTrue(request.uploadFiles().isEmpty());
    }

    @Test
    void streamingRequestExposesStreamOnceAndBodyMaterializesIt() throws Exception {
        byte[] payload = "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        WebResourceRequest request = WebResourceRequest.streaming("POST", URI.create("file://record/attachments"),
                Map.of(), WebResourceType.XHR, "", new java.io.ByteArrayInputStream(payload), payload.length, List.of());

        assertEquals(java.util.OptionalLong.of(5), request.contentLength());
        assertArrayEquals(payload, request.body(), "body() reads the whole stream");
        assertArrayEquals(payload, request.body(), "a second body() returns the same bytes without rereading");
        assertArrayEquals(payload, request.bodyStream().readAllBytes(), "bodyStream reads the stored bytes afterwards");
    }

    @Test
    void streamCanBeConsumedOnlyOnce() {
        WebResourceRequest request = WebResourceRequest.streaming("POST", URI.create("file://record/attachments"),
                Map.of(), WebResourceType.XHR, "", new java.io.ByteArrayInputStream(new byte[] {1}), -1, List.of());

        assertEquals(java.util.OptionalLong.empty(), request.contentLength());
        request.bodyStream();
        assertThrows(IllegalStateException.class, request::bodyStream);
        assertThrows(IllegalStateException.class, request::body);
    }

    @Test
    void byteRequestKeepsOldSemantics() throws Exception {
        WebResourceRequest request = new WebResourceRequest("POST", URI.create("file://record/images"),
                Map.of(), WebResourceType.XHR, "", new byte[] {1, 2});

        assertEquals(java.util.OptionalLong.of(2), request.contentLength());
        assertArrayEquals(new byte[] {1, 2}, request.body());
        assertEquals(2, request.bodyStream().available());
    }
}
