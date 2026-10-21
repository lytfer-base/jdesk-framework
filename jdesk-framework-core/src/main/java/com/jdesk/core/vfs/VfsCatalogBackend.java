package com.jdesk.core.vfs;

/**
 * Opaque backend descriptor used when opening a managed VFS catalog.
 *
 * <p>Core defines this marker so {@link ManagedVfsService} can stay independent
 * from frame storage or SQLite classes. Frame implementations can wrap concrete
 * resources, such as a database handle, in their own backend type.
 */
public interface VfsCatalogBackend {

    /**
     * Returns a stable diagnostic ID for logs and error messages.
     */
    String backendId();
}
