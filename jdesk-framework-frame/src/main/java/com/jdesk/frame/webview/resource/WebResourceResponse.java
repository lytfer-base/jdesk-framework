package com.jdesk.frame.webview.resource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Status, headers and body returned to the embedded browser; the body is bytes or a file the server streams
 * ({@link #ofFile}).
 */
public final class WebResourceResponse {

    private final int statusCode;
    private final Map<String, String> headers;
    private final byte[] body;
    private final Path file;

    private WebResourceResponse(int statusCode, Map<String, String> headers, byte[] body, Path file) {
        if (statusCode < 100 || statusCode > 599) {
            throw new IllegalArgumentException("statusCode must be a valid HTTP status: " + statusCode);
        }
        this.statusCode = statusCode;
        this.headers = Map.copyOf(headers == null ? Map.of() : headers);
        this.body = body == null ? new byte[0] : body.clone();
        this.file = file;
    }

    public static WebResourceResponse of(int statusCode, Map<String, String> headers, byte[] body) {
        return new WebResourceResponse(statusCode, headers, body, null);
    }

    public static WebResourceResponse ok(String contentType, byte[] body) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", normalizedContentType(contentType));
        headers.put("Cache-Control", "no-cache");
        return new WebResourceResponse(200, headers, body, null);
    }

    public static WebResourceResponse status(int statusCode, String message) {
        byte[] bytes = (message == null ? "" : message).getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "text/plain; charset=UTF-8");
        headers.put("Cache-Control", "no-cache");
        return new WebResourceResponse(statusCode, headers, bytes, null);
    }

    /** Creates a file response; the server streams it and handles Range and 304, so the handler reads no bytes. */
    public static WebResourceResponse ofFile(int statusCode, Map<String, String> headers, Path file) {
        return new WebResourceResponse(statusCode, headers, new byte[0], Objects.requireNonNull(file, "file"));
    }

    public static WebResourceResponse ofFile(String contentType, Path file) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", normalizedContentType(contentType));
        headers.put("Cache-Control", "no-cache");
        return ofFile(200, headers, file);
    }

    public WebResourceResponse withHeader(String name, String value) {
        if (name == null || name.isBlank() || value == null) {
            return this;
        }
        Map<String, String> copy = new LinkedHashMap<>(headers);
        copy.put(name.trim(), value);
        return new WebResourceResponse(statusCode, copy, body, file);
    }

    public WebResourceResponse withoutBody() {
        return new WebResourceResponse(statusCode, headers, new byte[0], null);
    }

    public int statusCode() {
        return statusCode;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public byte[] body() {
        return body.clone();
    }

    /** Returns the file of a file response, or empty for a byte response. */
    public Optional<Path> file() {
        return Optional.ofNullable(file);
    }

    private static String normalizedContentType(String contentType) {
        return contentType == null || contentType.isBlank()
                ? "application/octet-stream"
                : contentType.trim();
    }
}
