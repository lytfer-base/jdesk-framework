package com.jdesk.core.vfs;

import java.util.Objects;

/**
 * Stable identifier for a file that has entered the managed VFS catalog.
 *
 * <p>The ID is assigned lazily by a {@link VfsCatalog} when a caller decides a
 * real file must be referenced long-term. It is not assigned to every file in a
 * mounted folder.
 */
public record VfsFileId(String value) {

    /**
     * Validates and stores the raw ID value.
     */
    public VfsFileId {
        value = requireId("file id", value);
    }

    /**
     * Creates a file ID from a persisted catalog value.
     */
    public static VfsFileId of(String value) {
        return new VfsFileId(value);
    }

    static String requireId(String label, String value) {
        String cleaned = Objects.requireNonNull(value, label).trim();
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return cleaned;
    }
}
