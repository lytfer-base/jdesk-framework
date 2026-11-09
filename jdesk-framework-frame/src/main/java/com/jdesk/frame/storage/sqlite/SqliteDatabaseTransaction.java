package com.jdesk.frame.storage.sqlite;

import com.jdesk.frame.storage.DatabaseTransaction;
import com.jdesk.frame.storage.StorageException;

import java.sql.Connection;

/** A write transaction bound to its owner, callback lifetime, and writer thread. */
final class SqliteDatabaseTransaction extends SqliteDatabaseSession implements DatabaseTransaction {
    private final SqliteDatabaseHandle owner;
    private final Thread thread = Thread.currentThread();
    private volatile boolean active = true;

    SqliteDatabaseTransaction(Connection connection, SqliteDatabaseHandle owner) {
        super(connection);
        this.owner = owner;
    }

    void requireOwner(SqliteDatabaseHandle expected) {
        if (owner != expected || !active || Thread.currentThread() != thread) {
            throw new StorageException("Transaction belongs to another handle, thread, or completed callback");
        }
    }

    void finish() {
        active = false;
    }
}
