package com.jdesk.core.history;

import java.util.Objects;

/**
 * A deleted entry: the head row path (see {@link LocalHistoryStore#headPath}), the last version, and the times of the
 * deletion and of the scheduled purge.
 */
public record DeletedEntry(HistoryKey key, String path, long deletedAt, long purgeAt, HistoryVersion lastVersion) {

    public DeletedEntry {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(path, "path");
    }
}
