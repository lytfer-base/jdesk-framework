package com.jdesk.core.vfs;

import java.util.Objects;
import java.util.Optional;

/**
 * Converts between the live {@link VirtualFile} tree and managed {@link VfsManagedFile} snapshot rows: tree to managed
 * through {@link VfsCatalog#findFile(java.nio.file.Path)}, managed to tree by wrapping the absolute path on the local
 * file system.
 */
public final class ManagedVfsBridge {

    private final VfsCatalog catalog;
    private final LocalFileSystem localFileSystem;

    public ManagedVfsBridge(VfsCatalog catalog, LocalFileSystem localFileSystem) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.localFileSystem = Objects.requireNonNull(localFileSystem, "localFileSystem");
    }

    /** Returns a live node for the managed file that reads and writes the real file. */
    public VirtualFile toVirtualFile(VfsManagedFile file) {
        Objects.requireNonNull(file, "file");
        return localFileSystem.wrap(file.absolutePath());
    }

    /** Returns the managed file for a live node, resolved in the catalog by its absolute path. */
    public Optional<VfsManagedFile> toManagedFile(VirtualFile file) {
        Objects.requireNonNull(file, "file");
        return catalog.findFile(java.nio.file.Path.of(file.getPath()));
    }
}
