package com.jdesk.core.history;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Policy of one history store: fold window, idle gap that ends an external activity, size limits, baseline limit,
 * retention of deleted entries and text detection. Fixed when the store opens; applications override what they need
 * and take the rest from {@link #defaults()}.
 */
public record HistoryPolicy(
        Duration foldWindow,
        Duration externalIdleGap,
        long maxObjectBytes,
        long eagerBaselineMaxBytes,
        long libraryCapBytes,
        Duration deletedRetention,
        Set<String> textExtensions
) {

    private static final Set<String> DEFAULT_TEXT_EXTENSIONS = Set.of(
            "md", "markdown", "txt", "json", "yaml", "yml", "csv", "xml", "html", "htm", "css",
            "js", "ts", "java", "py", "sh", "toml", "ini", "log", "properties");

    public HistoryPolicy {
        Objects.requireNonNull(foldWindow, "foldWindow");
        Objects.requireNonNull(externalIdleGap, "externalIdleGap");
        Objects.requireNonNull(deletedRetention, "deletedRetention");
        textExtensions = Set.copyOf(Objects.requireNonNull(textExtensions, "textExtensions"));
        if (maxObjectBytes <= 0 || eagerBaselineMaxBytes < 0 || libraryCapBytes <= 0) {
            throw new IllegalArgumentException("size limits must be positive");
        }
    }

    public static HistoryPolicy defaults() {
        return new HistoryPolicy(
                Duration.ofMinutes(5),
                Duration.ofSeconds(60),
                20L * 1024 * 1024,
                64L * 1024,
                1024L * 1024 * 1024,
                Duration.ofDays(30),
                DEFAULT_TEXT_EXTENSIONS);
    }

    /** Returns whether the file extension is a text one; false otherwise, leaving content sniffing to the caller. */
    public boolean isTextName(String path) {
        if (path == null) {
            return false;
        }
        String name = path;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return false;
        }
        return textExtensions.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
