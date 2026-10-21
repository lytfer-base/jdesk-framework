package com.jdesk.core.history;

import java.util.Objects;

/**
 * One row of the version log. A null {@code objectHash} means only the event was recorded, without content (too
 * large); {@code normHash}, for text only, hashes the content with normalized line endings to spot changes in line
 * endings alone. {@code activityParams} is the parameter text given when the activity began, or null.
 */
public record HistoryVersion(
        long versionId,
        HistoryKey key,
        String path,
        String objectHash,
        String normHash,
        long size,
        long capturedAt,
        HistorySource source,
        Long activityId,
        String activityName,
        String activityParams,
        String label,
        boolean sealed,
        String skippedReason
) {

    public HistoryVersion {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(source, "source");
    }

    public boolean hasContent() {
        return objectHash != null;
    }
}
