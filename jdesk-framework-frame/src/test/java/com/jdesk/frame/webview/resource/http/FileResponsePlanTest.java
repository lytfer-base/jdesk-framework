package com.jdesk.frame.webview.resource.http;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileResponsePlanTest {

    private static final Map<String, String> HEADERS = Map.of("Content-Type", "video/mp4", "Cache-Control", "no-cache");
    private static final long MTIME = 1_700_000_000_000L;

    @Test
    void fullGetHasLengthEtagLastModifiedAndAcceptRanges() {
        FileResponsePlan plan = FileResponsePlan.of("GET", 1000, MTIME, HEADERS, null, null, null);

        assertEquals(200, plan.status());
        assertEquals(0, plan.offset());
        assertEquals(1000, plan.length());
        assertTrue(plan.writeBody());
        assertEquals("1000", plan.headers().get("Content-Length"));
        assertEquals("bytes", plan.headers().get("Accept-Ranges"));
        assertEquals("video/mp4", plan.headers().get("Content-Type"));
        assertTrue(plan.headers().get("ETag").startsWith("W/\""));
        assertEquals("Tue, 14 Nov 2023 22:13:20 GMT", plan.headers().get("Last-Modified"));
    }

    @Test
    void headWritesNoBodyButKeepsLength() {
        FileResponsePlan plan = FileResponsePlan.of("HEAD", 1000, MTIME, HEADERS, null, null, null);
        assertEquals(200, plan.status());
        assertFalse(plan.writeBody());
        assertEquals("1000", plan.headers().get("Content-Length"));
    }

    @Test
    void singleRangeForms() {
        FileResponsePlan mid = FileResponsePlan.of("GET", 1000, MTIME, HEADERS, "bytes=100-199", null, null);
        assertEquals(206, mid.status());
        assertEquals(100, mid.offset());
        assertEquals(100, mid.length());
        assertEquals("bytes 100-199/1000", mid.headers().get("Content-Range"));
        assertEquals("100", mid.headers().get("Content-Length"));

        FileResponsePlan open = FileResponsePlan.of("GET", 1000, MTIME, HEADERS, "bytes=900-", null, null);
        assertEquals(900, open.offset());
        assertEquals(100, open.length());

        FileResponsePlan suffix = FileResponsePlan.of("GET", 1000, MTIME, HEADERS, "bytes=-50", null, null);
        assertEquals(950, suffix.offset());
        assertEquals(50, suffix.length());

        FileResponsePlan clamped = FileResponsePlan.of("GET", 1000, MTIME, HEADERS, "bytes=0-5000", null, null);
        assertEquals(1000, clamped.length());
    }

    @Test
    void unsatisfiableOrMalformedRange() {
        FileResponsePlan beyond = FileResponsePlan.of("GET", 1000, MTIME, HEADERS, "bytes=1000-", null, null);
        assertEquals(416, beyond.status());
        assertFalse(beyond.writeBody());
        assertEquals("bytes */1000", beyond.headers().get("Content-Range"));

        assertEquals(200, FileResponsePlan.of("GET", 1000, MTIME, HEADERS, "items=1-2", null, null).status(),
                "an unknown unit is treated as no Range");
        assertEquals(200, FileResponsePlan.of("GET", 1000, MTIME, HEADERS, "bytes=1-2,5-6", null, null).status(),
                "multiple ranges are treated as no Range");
    }

    @Test
    void conditionalRequestsYield304() {
        String etag = FileResponsePlan.of("GET", 1000, MTIME, HEADERS, null, null, null).headers().get("ETag");

        FileResponsePlan byEtag = FileResponsePlan.of("GET", 1000, MTIME, HEADERS, null, etag, null);
        assertEquals(304, byEtag.status());
        assertFalse(byEtag.writeBody());
        assertNull(byEtag.headers().get("Content-Length"));

        FileResponsePlan byDate = FileResponsePlan.of("GET", 1000, MTIME, HEADERS, null, null,
                "Tue, 14 Nov 2023 22:13:20 GMT");
        assertEquals(304, byDate.status());

        assertEquals(200, FileResponsePlan.of("GET", 1000, MTIME, HEADERS, null, "\"other\"", null).status());
        assertEquals(200, FileResponsePlan.of("GET", 1000, MTIME, HEADERS, null, null,
                "Tue, 14 Nov 2023 22:13:19 GMT").status());
        assertEquals(200, FileResponsePlan.of("GET", 1000, MTIME, HEADERS, null, null, "garbage").status());
    }
}
