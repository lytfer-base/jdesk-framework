package com.jdesk.core.vfs;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Scoped catalog that assigns and resolves stable IDs for managed files. A catalog belongs to one scope, which opens
 * it lazily and should close it when the scope is no longer active.
 */
public interface VfsCatalog extends AutoCloseable {

    /**
     * Returns the ID of the scope this catalog belongs to.
     */
    String scopeId();

    /**
     * Registers a root under the caller's stable key, or returns the existing {@link VfsRootId} for that key. The key
     * doesn't depend on the location, so a root moved as a whole (another drive, a restored backup) keeps its ID and
     * every {@link VfsFileId} and piece of metadata under it. Use a constant as the key; don't use the path.
     */
    VfsRootId registerRoot(String rootKey, String name, Path rootPath);

    /**
     * Lists the roots known to this catalog scope.
     */
    List<VfsRoot> listRoots();

    /**
     * Registers one managed file under a root or returns the existing record for
     * the same root/path pair.
     */
    VfsManagedFile registerFile(VfsRootId rootId, String relativePath, boolean directory);

    /**
     * Resolves a managed file by its stable ID.
     */
    Optional<VfsManagedFile> findFile(VfsFileId fileId);

    /**
     * Resolves a managed file by root and normalized relative path.
     */
    Optional<VfsManagedFile> findFile(VfsRootId rootId, String relativePath);

    /**
     * Moves a row to a new path, keeping its {@link VfsFileId}. Another {@link VfsFileStatus#MISSING} row at the target
     * is deleted without passing on its identity or flags; if a {@link VfsFileStatus#PRESENT} row holds the target, an
     * implementation-specific unchecked exception is thrown and nothing changes.
     */
    VfsManagedFile moveFile(VfsFileId fileId, VfsRootId newRootId, String newRelativePath);

    /**
     * Updates the lifecycle status of a managed file record.
     */
    VfsManagedFile updateStatus(VfsFileId fileId, VfsFileStatus status);

    /**
     * Sets or clears the user read-only flag and returns whether the row exists, in any status; publishes no event.
     * The flag stays on the row through {@link #moveFile} and reconciliation; a path registered again after its row
     * was removed gets a new identity with the flag cleared.
     */
    boolean setReadOnly(VfsFileId fileId, boolean readOnly);

    /**
     * Deletes one snapshot row, the reverse of {@link #registerFile}; a missing row is ignored. Remove the row once a
     * path stops being managed: a leftover row hands its {@link VfsFileId} to the next object registered at that path.
     * Doesn't cascade; for a subtree, pass every row to {@link #removeFiles} at once instead of calling this per row.
     */
    void removeFile(VfsFileId fileId);

    /**
     * Deletes all the given snapshot rows in one transaction, so either all of them are gone or none changed; unknown
     * IDs are ignored. Deleting a subtree must go through here with the directory and every row under it.
     */
    void removeFiles(Collection<VfsFileId> fileIds);

    /**
     * Reconciles the children of {@code relativePath} (the whole subtree if {@code recursive}; empty means the root)
     * with the disk, writes the differences into the snapshot and returns them without publishing events. A cancelled
     * walk throws {@link java.util.concurrent.CancellationException} and writes nothing.
     */
    VfsScanResult refresh(VfsRootId rootId, String relativePath, boolean recursive, ScanFilter filter);

    /**
     * Reconciles one file: rereads its attributes into the snapshot, bumps {@code content_version} if the size or
     * mtime changed and marks it {@link VfsFileStatus#MISSING} if it is gone; publishes no event. Writers call it right
     * after writing a file; don't call it on read paths. An unregistered {@code fileId} throws an unchecked exception.
     */
    VfsManagedFile refreshFile(VfsFileId fileId);

    /** Reconciles the whole root; same as {@code refresh(rootId, "", true, filter)}. */
    default VfsScanResult scanRoot(VfsRootId rootId, ScanFilter filter) {
        return refresh(rootId, "", true, filter);
    }

    /**
     * Lists the direct children of a directory from the snapshot without reading the disk, directories first and then
     * by case-insensitive name, PRESENT and MISSING rows alike; an empty {@code dirRelativePath} means the root.
     */
    List<VfsManagedFile> listChildren(VfsRootId rootId, String dirRelativePath);

    /**
     * Lists every snapshot row under the root, directories and MISSING rows included, in no particular order. The
     * default walks {@link #listChildren} level by level; storage implementations should override it with one query.
     */
    default List<VfsManagedFile> listAll(VfsRootId rootId) {
        List<VfsManagedFile> all = new java.util.ArrayList<>();
        java.util.Deque<String> dirs = new java.util.ArrayDeque<>();
        dirs.push("");
        while (!dirs.isEmpty()) {
            for (VfsManagedFile entry : listChildren(rootId, dirs.pop())) {
                all.add(entry);
                if (entry.directory()) {
                    dirs.push(entry.relativePath());
                }
            }
        }
        return all;
    }

    /** Resolves an absolute disk path to a managed file by matching it against the roots of this catalog. */
    Optional<VfsManagedFile> findFile(Path absolutePath);

    /**
     * Releases resources owned by this scoped catalog.
     */
    @Override
    void close();
}
