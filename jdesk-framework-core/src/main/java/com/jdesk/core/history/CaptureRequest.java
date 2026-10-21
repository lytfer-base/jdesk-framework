package com.jdesk.core.history;

import java.util.Objects;

/** One capture: key, display path, content and source; {@code label} is set only for explicit snapshots. */
public record CaptureRequest(HistoryKey key, String path, ContentSource content, HistorySource source, String label) {

    public CaptureRequest {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(source, "source");
    }

    public static CaptureRequest of(HistoryKey key, String path, ContentSource content, HistorySource source) {
        return new CaptureRequest(key, path, content, source, null);
    }
}
