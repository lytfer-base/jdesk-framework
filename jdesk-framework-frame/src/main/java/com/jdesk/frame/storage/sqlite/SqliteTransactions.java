package com.jdesk.frame.storage.sqlite;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Runs one SQLite transaction on a connection: commits on normal return and rolls back on any failure.
 * Don't restore auto-commit before the transaction is settled: sqlite-jdbc's {@code setAutoCommit(true)} commits
 * whatever writes a failed commit or rollback left pending.
 */
final class SqliteTransactions {

    /** JDBC work to run inside the transaction. */
    @FunctionalInterface
    interface Work<T> {
        T run() throws SQLException;
    }

    private SqliteTransactions() {
    }

    /**
     * Runs {@code work} in a transaction, restoring the auto-commit setting after a successful commit or rollback; if
     * the rollback fails too, the setting is left as is and the caller closes the connection. Cleanup failures are
     * added as suppressed to the original exception, which is rethrown.
     */
    static <T> T run(Connection connection, Work<T> work) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        T result;
        try {
            result = work.run();
            connection.commit();
        } catch (Throwable failure) {
            try {
                connection.rollback();
                connection.setAutoCommit(autoCommit);
            } catch (SQLException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        connection.setAutoCommit(autoCommit);
        return result;
    }
}
