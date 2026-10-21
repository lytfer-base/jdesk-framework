package com.jdesk.core.vfs;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Immutable copy of a managed file snapshot row: identity, basic disk state and the user read-only flag. Content
 * caches, editing sessions and search indexes attach their own data by {@link #fileId()}, outside the catalog.
 */
public record VfsManagedFile(
        VfsFileId fileId,
        VfsRootId rootId,
        String relativePath,
        Path absolutePath,
        VfsFileStatus status,
        boolean directory,
        long size,
        long modifiedAt,
        long contentVersion,
        /** Creation time in epoch milliseconds, from {@code BasicFileAttributes.creationTime()} at scan time. */
        long fileCreatedAt,
        /** The user read-only flag ({@link VfsCatalog#setReadOnly}), not the operating system attribute. */
        boolean readOnly
) {

    /**
     * Normalizes paths and validates required identity fields.
     */
    public VfsManagedFile {
        fileId = Objects.requireNonNull(fileId, "fileId");
        rootId = Objects.requireNonNull(rootId, "rootId");
        relativePath = normalizeRelativePath(relativePath);
        absolutePath = Objects.requireNonNull(absolutePath, "absolutePath").toAbsolutePath().normalize();
        status = Objects.requireNonNull(status, "status");
    }

    /** Creates a row with {@code readOnly} false; don't copy an existing row with it, or the user's flag is lost. */
    public VfsManagedFile(VfsFileId fileId, VfsRootId rootId, String relativePath, Path absolutePath,
                          VfsFileStatus status, boolean directory, long size, long modifiedAt,
                          long contentVersion, long fileCreatedAt) {
        this(fileId, rootId, relativePath, absolutePath, status, directory, size, modifiedAt,
                contentVersion, fileCreatedAt, false);
    }

    /**
     * Converts caller-facing paths into the catalog's stable slash-separated
     * representation.
     */
    public static String normalizeRelativePath(String relativePath) {
        String cleaned = VfsFileId.requireId("relativePath", relativePath).replace('\\', '/');
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.equals(".") || cleaned.equals("..")
                || cleaned.startsWith("../")
                || cleaned.contains("/../")
                || cleaned.endsWith("/..")) {
            throw new IllegalArgumentException("relativePath must stay inside the VFS root: " + relativePath);
        }
        return cleaned;
    }
}
