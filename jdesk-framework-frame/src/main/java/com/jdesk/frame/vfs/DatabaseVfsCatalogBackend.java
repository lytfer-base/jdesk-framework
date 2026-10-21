package com.jdesk.frame.vfs;

import com.jdesk.core.vfs.VfsCatalogBackend;
import com.jdesk.frame.storage.DatabaseHandle;

import java.util.Objects;

/**
 * Frame-side VFS catalog backend backed by a {@link DatabaseHandle}.
 *
 * <p>This wrapper keeps the core VFS service API independent from frame storage
 * types while still allowing VFS catalog tables to live in the same SQLite file
 * as application business tables.
 */
public final class DatabaseVfsCatalogBackend implements VfsCatalogBackend {

    private final DatabaseHandle database;

    private DatabaseVfsCatalogBackend(DatabaseHandle database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    /**
     * Wraps an existing database handle for managed VFS catalog creation.
     */
    public static DatabaseVfsCatalogBackend of(DatabaseHandle database) {
        return new DatabaseVfsCatalogBackend(database);
    }

    /**
     * Returns the database handle used by the SQLite catalog implementation.
     */
    public DatabaseHandle database() {
        return database;
    }

    /**
     * Returns a stable diagnostic ID for the wrapped database.
     */
    @Override
    public String backendId() {
        return database.id();
    }
}
