package com.jdesk.frame.storage;

import java.util.Optional;

/**
 * Entry point for framework-managed databases: opens or creates database files, runs migrations, applies backend
 * options and tracks open handles. Callers work through the returned {@link DatabaseHandle}.
 */
public interface DatabaseService {

    /**
     * Opens the database, or returns the handle already open for its id. There is one handle per id per process and
     * no reference counting, so only the owner that manages the database's lifecycle may close it.
     * Callers that only use the database must call {@link #borrow(DatabaseDescriptor)} instead.
     */
    DatabaseHandle open(DatabaseDescriptor descriptor);

    /**
     * Borrows the database shared under the descriptor's id, opening it if needed. The returned handle can't be
     * closed: {@code close} and {@code dispose} do nothing, so it is safe to register under your own Disposer parent.
     */
    default DatabaseHandle borrow(DatabaseDescriptor descriptor) {
        return new BorrowedDatabaseHandle(open(descriptor));
    }

    /**
     * Returns the currently open handle for a database id, if the handle exists
     * and has not been disposed.
     */
    Optional<DatabaseHandle> findOpen(String databaseId);

    /**
     * Closes and unregisters the handle for {@code databaseId}; owner only, since every other holder loses it too.
     *
     * @return {@code true} when an open database was found and closed
     */
    boolean close(String databaseId);
}
