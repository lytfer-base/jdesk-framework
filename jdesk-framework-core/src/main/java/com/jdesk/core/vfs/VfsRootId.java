package com.jdesk.core.vfs;

/**
 * Stable identifier for a managed VFS root inside one catalog scope.
 *
 * <p>A root usually maps to a local folder selected by the application or the
 * user. File IDs are unique inside the catalog and refer to one of these roots.
 */
public record VfsRootId(String value) {

    /**
     * Validates and stores the raw root ID value.
     */
    public VfsRootId {
        value = VfsFileId.requireId("root id", value);
    }

    /**
     * Creates a root ID from a persisted catalog value.
     */
    public static VfsRootId of(String value) {
        return new VfsRootId(value);
    }
}
