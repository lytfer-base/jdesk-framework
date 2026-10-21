package com.jdesk.frame.webview.resource.http;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * How to write a file response: status, byte range and headers, computed without I/O. A single-part Range gets 206,
 * an unsatisfiable one 416, a matching ETag or Last-Modified 304, and HEAD no body.
 */
public record FileResponsePlan(int status, long offset, long length, Map<String, String> headers, boolean writeBody) {

    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    public static FileResponsePlan of(String method, long size, long lastModifiedMillis,
                                      Map<String, String> handlerHeaders,
                                      String range, String ifNoneMatch, String ifModifiedSince) {
        long lastModifiedSeconds = lastModifiedMillis / 1000;
        String etag = "W/\"" + Long.toHexString(size) + "-" + Long.toHexString(lastModifiedSeconds) + "\"";
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : handlerHeaders.entrySet()) {
            if (!"Content-Length".equalsIgnoreCase(entry.getKey())) {
                headers.put(entry.getKey(), entry.getValue());
            }
        }
        headers.put("ETag", etag);
        headers.put("Last-Modified", HTTP_DATE.format(Instant.ofEpochSecond(lastModifiedSeconds)));
        headers.put("Accept-Ranges", "bytes");
        boolean head = "HEAD".equalsIgnoreCase(method);

        if (notModified(etag, lastModifiedSeconds, ifNoneMatch, ifModifiedSince)) {
            return new FileResponsePlan(304, 0, 0, headers, false);
        }
        long[] span = rangeOf(range, size);
        if (span == null) {
            headers.put("Content-Length", Long.toString(size));
            return new FileResponsePlan(200, 0, size, headers, !head);
        }
        if (span.length == 0) {
            headers.put("Content-Range", "bytes */" + size);
            return new FileResponsePlan(416, 0, 0, headers, false);
        }
        long length = span[1] - span[0] + 1;
        headers.put("Content-Range", "bytes " + span[0] + "-" + span[1] + "/" + size);
        headers.put("Content-Length", Long.toString(length));
        return new FileResponsePlan(206, span[0], length, headers, !head);
    }

    private static boolean notModified(String etag, long lastModifiedSeconds, String ifNoneMatch, String ifModifiedSince) {
        if (ifNoneMatch != null) {
            for (String candidate : ifNoneMatch.split(",")) {
                String trimmed = candidate.trim();
                if ("*".equals(trimmed) || etag.equals(trimmed) || etag.equals("W/" + trimmed)) {
                    return true;
                }
            }
            return false;
        }
        if (ifModifiedSince == null) {
            return false;
        }
        try {
            long since = Instant.from(HTTP_DATE.parse(ifModifiedSince.trim())).getEpochSecond();
            return lastModifiedSeconds <= since;
        } catch (DateTimeParseException | ArithmeticException malformed) {
            return false;
        }
    }

    /**
     * Returns {first, last}, an empty array if unsatisfiable, or null without a usable Range; multiple ranges and
     * units other than bytes count as none.
     */
    private static long[] rangeOf(String range, long size) {
        if (range == null || !range.trim().toLowerCase(Locale.ROOT).startsWith("bytes=")) {
            return null;
        }
        String spec = range.trim().substring("bytes=".length()).trim();
        int dash = spec.indexOf('-');
        if (spec.contains(",") || dash < 0) {
            return null;
        }
        String first = spec.substring(0, dash).trim();
        String last = spec.substring(dash + 1).trim();
        try {
            if (first.isEmpty()) {
                long suffix = Long.parseLong(last);
                if (suffix <= 0 || size == 0) {
                    return new long[0];
                }
                return new long[] {Math.max(0, size - suffix), size - 1};
            }
            long start = Long.parseLong(first);
            long end = last.isEmpty() ? size - 1 : Math.min(Long.parseLong(last), size - 1);
            if (start < 0 || start >= size || end < start) {
                return new long[0];
            }
            return new long[] {start, end};
        } catch (NumberFormatException malformed) {
            return null;
        }
    }
}
