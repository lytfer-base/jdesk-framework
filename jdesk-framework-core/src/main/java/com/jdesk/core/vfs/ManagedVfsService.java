package com.jdesk.core.vfs;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Application-level service that opens and closes scoped managed VFS catalogs. Catalogs aren't loaded at startup;
 * callers open one only while its scope is in active use.
 */
public interface ManagedVfsService {

    /**
     * Opens or returns the catalog for one scope using the supplied backend; {@code locale} sets the collation of the
     * stored file name sort keys. An open catalog ignores a later {@code locale} but one rebuilt after going stale uses
     * it, so always pass the same constant locale for a scope, or the store ends up with mixed sort keys.
     */
    VfsCatalog openCatalog(String scopeId, VfsCatalogBackend catalogBackend, Locale locale);

    /**
     * Opens with {@link Locale#getDefault()}, which follows the UI language; for stable stored sort keys use
     * {@link #openCatalog(String, VfsCatalogBackend, Locale)} with a fixed locale.
     */
    default VfsCatalog openCatalog(String scopeId, VfsCatalogBackend catalogBackend) {
        return openCatalog(scopeId, catalogBackend, Locale.getDefault());
    }

    /**
     * Returns an already-open catalog without creating a new one.
     */
    Optional<VfsCatalog> findOpenCatalog(String scopeId);

    /**
     * Closes and unregisters a catalog if it is currently open.
     */
    boolean closeCatalog(String scopeId);

    /**
     * Starts watching the root subtree, so changes refresh the scope's catalog and publish change events. The catalog
     * must be open already, {@code filter} must match the one the scope scans with, and watching twice is a no-op.
     */
    void watch(String scopeId, VfsRootId rootId, Path root, ScanFilter filter);

    /** Stops watching the scope and disposes its watcher; does nothing if the scope isn't watched. */
    void unwatch(String scopeId);

    /**
     * Reconciles the scope's whole root tree right away and publishes the changes the way the watcher does; does
     * nothing if the scope isn't watched. Backs an explicit "sync with disk" command for when the watcher misses
     * events, for example on network drives or after an event overflow.
     */
    void syncFromDisk(String scopeId);

    /**
     * Publishes {@code created} files as added and {@code updated} ones as updated after the application wrote them
     * and registered them in the snapshot itself; does nothing if the scope isn't watched. Writers must call it: the
     * watcher then finds no difference to report, and neither does {@link #syncFromDisk}.
     */
    void notifyChanged(String scopeId, List<VfsFileId> created, List<VfsFileId> updated);

    /**
     * Called by a writer right after writing a file: reconciles it, hands the new content to local history with
     * source APP and returns the refreshed row; a scope without history is only reconciled. Use it instead of
     * {@code catalog.refreshFile}.
     */
    VfsManagedFile fileWritten(String scopeId, VfsFileId fileId);

    /**
     * Called when the application opens a file: records a baseline for a text file that has no history yet; does
     * nothing if the scope has no history.
     */
    void fileOpened(String scopeId, VfsFileId fileId);

    /**
     * Reconciles the whole tree: records the differences, feeds local history (changes, deleted missing files and
     * baselines for small text files) and publishes if the scope is watched. Use it, not {@code catalog.scanRoot}; a
     * cancelled walk throws {@link java.util.concurrent.CancellationException} and writes nothing.
     */
    VfsScanResult reconcile(String scopeId, VfsRootId rootId, ScanFilter filter);
}
