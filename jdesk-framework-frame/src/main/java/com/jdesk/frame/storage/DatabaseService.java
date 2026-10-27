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
     * Borrows the shared database, opening it if needed, and retains the descriptor and current path policies.
     * Closing or disposing the returned view does not close its owner.
     */
    default DatabaseHandle borrow(DatabaseDescriptor descriptor) {
        return new BorrowedDatabaseHandle(open(descriptor), descriptor.options().pathAccess().capture());
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
