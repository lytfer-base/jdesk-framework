package com.jdesk.frame.webview.jcef;

import com.jdesk.frame.webview.resource.UploadPathRewrite;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Rebuilds multipart segments from CEF post data, a mix of byte elements (part headers and string fields) and file
 * elements (a disk path each).
 * Stay strict and return empty on anything unexpected: the caller then sends the real multipart body unchanged.
 */
final class JcefMultipartSegments {

    /** One post data element: text for a byte element, a disk path for a file element. */
    record Element(String text, String filePath) {

        static Element bytes(String text) {
            return new Element(text, null);
        }

        static Element file(String filePath) {
            return new Element(null, filePath);
        }

        boolean isFile() {
            return filePath != null;
        }
    }

    private JcefMultipartSegments() {
    }

    static Optional<List<UploadPathRewrite.Segment>> parse(String contentType, List<Element> elements) {
        Optional<String> boundary = boundaryOf(contentType);
        if (boundary.isEmpty() || elements.isEmpty()) {
            return Optional.empty();
        }
        String delimiter = "--" + boundary.get();
        List<UploadPathRewrite.Segment> segments = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        for (Element element : elements) {
            if (!element.isFile()) {
                if (element.text() == null) {
                    return Optional.empty();
                }
                buffer.append(element.text());
                continue;
            }
            Drained drained = drainCompleteParts(buffer, delimiter, segments);
            if (!drained.ok() || drained.trailingHeaders() == null) {
                return Optional.empty();
            }
            Optional<UploadPathRewrite.Segment> fileSegment =
                    fileSegment(drained.trailingHeaders(), element.filePath());
            if (fileSegment.isEmpty()) {
                return Optional.empty();
            }
            segments.add(fileSegment.get());
        }
        // Only the closing delimiter may remain; leftover part headers mean an unknown shape.
        Drained tail = drainCompleteParts(buffer, delimiter, segments);
        return tail.ok() && tail.trailingHeaders() == null ? Optional.of(segments) : Optional.empty();
    }

    /** {@code ok} is false for an unknown shape; {@code trailingHeaders} are headers still awaiting a body, or null. */
    private record Drained(boolean ok, String trailingHeaders) {

        static final Drained FAILED = new Drained(false, null);
    }

    /**
     * Turns every complete part in the buffer into a string field; the headers of a final part without a body are
     * returned, since they belong to the file element that follows.
     */
    private static Drained drainCompleteParts(StringBuilder buffer, String delimiter,
                                              List<UploadPathRewrite.Segment> segments) {
        String rest = buffer.toString();
        buffer.setLength(0);
        String[] chunks = rest.split(java.util.regex.Pattern.quote(delimiter), -1);
        String trailingHeaders = null;
        for (int i = 1; i < chunks.length; i++) {
            String chunk = chunks[i];
            if (chunk.startsWith("--")) {
                continue; // Closing delimiter.
            }
            int blank = chunk.indexOf("\r\n\r\n");
            if (blank < 0) {
                return Drained.FAILED;
            }
            String headers = chunk.substring(0, blank);
            String body = chunk.substring(blank + 4);
            if (body.isEmpty() && i == chunks.length - 1) {
                trailingHeaders = headers;
                continue;
            }
            if (!body.endsWith("\r\n")) {
                return Drained.FAILED;
            }
            Optional<String> name = attribute(headers, "name");
            if (name.isEmpty()) {
                return Drained.FAILED;
            }
            segments.add(new UploadPathRewrite.Segment(name.get(),
                    body.substring(0, body.length() - 2), null, null));
        }
        return new Drained(true, trailingHeaders);
    }

    private static Optional<UploadPathRewrite.Segment> fileSegment(String headers, String filePath) {
        Optional<String> name = attribute(headers, "name");
        if (name.isEmpty() || filePath == null || filePath.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new UploadPathRewrite.Segment(name.get(), null,
                attribute(headers, "filename").orElse(""), filePath));
    }

    /** Reads {@code name="..."} or {@code filename="..."} from the {@code Content-Disposition} header. */
    private static Optional<String> attribute(String headers, String key) {
        String marker = key + "=\"";
        int start = headers.indexOf(marker);
        if (start < 0) {
            return Optional.empty();
        }
        int from = start + marker.length();
        int end = headers.indexOf('"', from);
        return end < 0 ? Optional.empty() : Optional.of(headers.substring(from, end));
    }

    /** Returns the multipart boundary, or empty if the body isn't multipart. */
    static Optional<String> boundaryOf(String contentType) {
        if (contentType == null) {
            return Optional.empty();
        }
        int marker = contentType.toLowerCase(java.util.Locale.ROOT).indexOf("boundary=");
        if (marker < 0) {
            return Optional.empty();
        }
        String value = contentType.substring(marker + "boundary=".length()).trim();
        if (value.startsWith("\"")) {
            int end = value.indexOf('"', 1);
            return end < 0 ? Optional.empty() : Optional.of(value.substring(1, end));
        }
        int semicolon = value.indexOf(';');
        String boundary = semicolon < 0 ? value : value.substring(0, semicolon);
        return boundary.isBlank() ? Optional.empty() : Optional.of(boundary.trim());
    }
}
