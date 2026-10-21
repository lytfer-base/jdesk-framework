package com.jdesk.frame.storage.sqlite;

import com.jdesk.frame.storage.DatabaseTransaction;

import java.sql.Connection;

/**
 * SQLite transaction session marker.
 *
 * <p>Commit and rollback are controlled by {@link SqliteDatabaseHandle}; this
 * class only narrows the session type exposed to transaction callbacks.
 */
final class SqliteDatabaseTransaction extends SqliteDatabaseSession implements DatabaseTransaction {

    /**
     * Wraps the transaction connection managed by the owning handle.
     */
    SqliteDatabaseTransaction(Connection connection) {
        super(connection);
    }
}
