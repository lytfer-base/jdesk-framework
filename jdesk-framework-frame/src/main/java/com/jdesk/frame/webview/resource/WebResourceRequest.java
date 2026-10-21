package com.jdesk.frame.webview.resource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * One resource request, with its body as bytes ({@link #body()}, for small requests) or as a stream
 * ({@link #bodyStream()}, readable once, for large uploads). Calling {@code body()} on a stream reads it whole; once
 * the stream was taken, either method throws {@link IllegalStateException}.
 */
public final class WebResourceRequest {

    private final String method;
    private final URI uri;
    private final Map<String, List<String>> headers;
    private final WebResourceType resourceType;
    private final String sourceUrl;
    private final List<WebResourceUploadFile> uploadFiles;
    private final long contentLength;
    private final Object bodyLock = new Object();
    private InputStream pendingStream;
    private byte[] bytes;
    private boolean streamConsumed;

    private WebResourceRequest(String method, URI uri, Map<String, List<String>> headers,
                               WebResourceType resourceType, String sourceUrl,
                               byte[] bytes, InputStream stream, long contentLength,
                               List<WebResourceUploadFile> uploadFiles) {
        this.method = Objects.requireNonNull(method, "method").trim().toUpperCase(Locale.ROOT);
        if (this.method.isEmpty()) {
            throw new IllegalArgumentException("method must not be blank");
        }
        this.uri = Objects.requireNonNull(uri, "uri");
        this.headers = immutableHeaders(headers);
        this.resourceType = resourceType == null ? WebResourceType.OTHER : resourceType;
        this.sourceUrl = Objects.toString(sourceUrl, "");
        this.uploadFiles = uploadFiles == null ? List.of() : List.copyOf(uploadFiles);
        this.bytes = bytes;
        this.pendingStream = stream;
        this.contentLength = contentLength;
    }

    /** Creates a request with a byte body and upload files. */
    public WebResourceRequest(String method, URI uri, Map<String, List<String>> headers,
                              WebResourceType resourceType, String sourceUrl, byte[] body,
                              List<WebResourceUploadFile> uploadFiles) {
        this(method, uri, headers, resourceType, sourceUrl,
                body == null ? new byte[0] : body.clone(), null, body == null ? 0 : body.length, uploadFiles);
    }

    /** Creates a request with a byte body and no upload files. */
    public WebResourceRequest(String method, URI uri, Map<String, List<String>> headers,
                              WebResourceType resourceType, String sourceUrl, byte[] body) {
        this(method, uri, headers, resourceType, sourceUrl, body, List.of());
    }

    /** Creates a request without a body, such as GET or HEAD. */
    public WebResourceRequest(String method, URI uri, Map<String, List<String>> headers,
                              WebResourceType resourceType, String sourceUrl) {
        this(method, uri, headers, resourceType, sourceUrl, new byte[0]);
    }

    /** Creates a request with a streamed body; pass -1 for an unknown length. Don't touch the stream afterwards. */
    public static WebResourceRequest streaming(String method, URI uri, Map<String, List<String>> headers,
                                               WebResourceType resourceType, String sourceUrl,
                                               InputStream body, long contentLength,
                                               List<WebResourceUploadFile> uploadFiles) {
        return new WebResourceRequest(method, uri, headers, resourceType, sourceUrl, null,
                Objects.requireNonNull(body, "body"), contentLength < 0 ? -1 : contentLength, uploadFiles);
    }

    public String method() {
        return method;
    }

    public URI uri() {
        return uri;
    }

    /** Returns the headers without Cookie, which the resource server strips, so the session token never shows here. */
    public Map<String, List<String>> headers() {
        return headers;
    }

    public WebResourceType resourceType() {
        return resourceType;
    }

    public String sourceUrl() {
        return sourceUrl;
    }

    public List<WebResourceUploadFile> uploadFiles() {
        return uploadFiles;
    }

    /** Returns the body length, empty for a stream of undeclared length. */
    public OptionalLong contentLength() {
        return contentLength < 0 ? OptionalLong.empty() : OptionalLong.of(contentLength);
    }

    /** Returns a copy of the whole body; on a stream the first call reads it to the end. */
    public byte[] body() {
        synchronized (bodyLock) {
            if (bytes == null) {
                if (streamConsumed) {
                    throw new IllegalStateException("Request body stream was already consumed.");
                }
                try {
                    bytes = pendingStream.readAllBytes();
                } catch (IOException failure) {
                    throw new UncheckedIOException("Failed to read request body.", failure);
                }
                pendingStream = null;
            }
            return bytes.clone();
        }
    }

    /** Returns the body as a stream: once for a streamed body, a fresh stream each time for a byte body. */
    public InputStream bodyStream() {
        synchronized (bodyLock) {
            if (bytes != null) {
                return new ByteArrayInputStream(bytes);
            }
            if (streamConsumed) {
                throw new IllegalStateException("Request body stream was already consumed.");
            }
            streamConsumed = true;
            InputStream stream = pendingStream;
            pendingStream = null;
            return stream;
        }
    }

    public Optional<String> firstHeader(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                return Optional.ofNullable(entry.getValue().get(0));
            }
        }
        return Optional.empty();
    }

    private static Map<String, List<String>> immutableHeaders(Map<String, List<String>> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            result.put(entry.getKey(), List.copyOf(new ArrayList<>(entry.getValue())));
        }
        return Map.copyOf(result);
    }
}
