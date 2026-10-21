package com.jdesk.frame.storage.sqlite;

import com.jdesk.frame.storage.DatabaseMigration;
import com.jdesk.frame.storage.StorageException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Runs ordered schema migrations for one namespace of a SQLite database, keeping state per namespace in
 * {@code jdesk_schema_version} and {@code jdesk_schema_migration_log}. {@link #MAIN_NAMESPACE} is the owning
 * descriptor's version line; other namespaces belong to components that keep tables in a database they don't own.
 * Only {@link #MAIN_NAMESPACE} may write {@code PRAGMA user_version}: the pragma holds a single integer.
 */
final class SqliteMigrationRunner {

    /** The owning descriptor's version line. Reserved; components must not use it. */
    static final String MAIN_NAMESPACE = "main";

    private final String namespace;

    /**
     * Creates a runner for one schema namespace.
     */
    SqliteMigrationRunner(String namespace) {
        this.namespace = namespace;
    }

    /**
     * Ensures migration metadata tables exist and executes pending migrations in
     * ascending version order.
     */
    void run(Connection connection, List<DatabaseMigration> declared) {
        requirePositiveUniqueVersions(declared);
        ensureSchemaTables(connection);
        int currentVersion = currentVersion(connection);
        List<DatabaseMigration> migrations = declared.stream()
                .sorted(Comparator.comparingInt(DatabaseMigration::version))
                .toList();
        for (DatabaseMigration migration : migrations) {
            if (migration.version() <= currentVersion) {
                continue;
            }
            runMigration(connection, migration);
            currentVersion = migration.version();
        }
        if (MAIN_NAMESPACE.equals(namespace)) {
            setUserVersion(connection, currentVersion);
        }
    }

    /** Versions must be positive and unique; otherwise a later migration would be skipped as already applied. */
    private void requirePositiveUniqueVersions(List<DatabaseMigration> declared) {
        Set<Integer> seen = new HashSet<>();
        for (DatabaseMigration migration : declared) {
            int version = migration.version();
            if (version <= 0 || !seen.add(version)) {
                throw new StorageException("Invalid migration version " + version + " (" + migration.name()
                        + ") in namespace " + namespace + ": versions must be positive and unique");
            }
        }
    }

    /**
     * Creates frame-owned metadata tables used to track schema state.
     */
    private void ensureSchemaTables(Connection connection) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    create table if not exists jdesk_schema_version (
                      namespace text primary key,
                      version integer not null,
                      updated_at integer not null
                    )
                    """);
            statement.execute("""
                    create table if not exists jdesk_schema_migration_log (
                      id text primary key,
                      namespace text not null,
                      version integer not null,
                      name text not null,
                      started_at integer not null,
                      finished_at integer,
                      success integer not null,
                      error_message text,
                      duration_ms integer
                    )
                    """);
        } catch (SQLException e) {
            throw new StorageException("Failed to create schema metadata tables", e);
        }
    }

    /**
     * Reads the current applied schema version for this namespace.
     */
    private int currentVersion(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement(
                "select version from jdesk_schema_version where namespace = ?")) {
            statement.setString(1, namespace);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt("version") : 0;
            }
        } catch (SQLException e) {
            throw new StorageException("Failed to read schema version", e);
        }
    }

    /**
     * Executes one migration in its own transaction and logs success/failure.
     */
    private void runMigration(Connection connection, DatabaseMigration migration) {
        long started = Instant.now().toEpochMilli();
        String logId = namespace + "-" + migration.version() + "-" + migration.name();
        try {
            SqliteTransactions.run(connection, () -> {
                migration.migrate(new SqliteDatabaseSession(connection));
                upsertVersion(connection, migration.version());
                insertLog(connection, logId, migration, started, true, null);
                return null;
            });
        } catch (RuntimeException | Error e) {
            insertFailureAfterRollback(connection, logId, migration, started, e);
            throw e;
        } catch (SQLException e) {
            insertFailureAfterRollback(connection, logId, migration, started, e);
            throw new StorageException("Migration failed: " + migration.name(), e);
        }
    }

    /** Stores the latest successfully applied schema version. */
    private void upsertVersion(Connection connection, int version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into jdesk_schema_version(namespace, version, updated_at)
                values (?, ?, ?)
                on conflict(namespace) do update set version = excluded.version, updated_at = excluded.updated_at
                """)) {
            statement.setString(1, namespace);
            statement.setInt(2, version);
            statement.setLong(3, Instant.now().toEpochMilli());
            statement.executeUpdate();
        }
    }

    /**
     * Writes one migration log row.
     */
    private void insertLog(Connection connection,
                           String logId,
                           DatabaseMigration migration,
                           long started,
                           boolean success,
                           String error) throws SQLException {
        long finished = Instant.now().toEpochMilli();
        try (PreparedStatement statement = connection.prepareStatement("""
                insert or replace into jdesk_schema_migration_log(
                  id, namespace, version, name, started_at, finished_at, success, error_message, duration_ms
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, logId);
            statement.setString(2, namespace);
            statement.setInt(3, migration.version());
            statement.setString(4, migration.name());
            statement.setLong(5, started);
            statement.setLong(6, finished);
            statement.setInt(7, success ? 1 : 0);
            statement.setString(8, error);
            statement.setLong(9, finished - started);
            statement.executeUpdate();
        }
    }

    /**
     * Logs a failed migration after rollback without masking the original exception.
     * Skip the log while a failed rollback leaves the connection in the transaction: don't restore auto-commit there,
     * it would commit the half-done migration.
     */
    private void insertFailureAfterRollback(Connection connection,
                                            String logId,
                                            DatabaseMigration migration,
                                            long started,
                                            Throwable failure) {
        try {
            if (!connection.getAutoCommit()) {
                return;
            }
            insertLog(connection, logId, migration, started, false, failure.getMessage());
        } catch (SQLException ignored) {
            // Logging a failed migration must not mask the original failure.
        }
    }

    /**
     * Mirrors the schema version into SQLite's built-in user_version pragma.
     */
    private void setUserVersion(Connection connection, int version) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version=" + version);
        } catch (SQLException e) {
            throw new StorageException("Failed to update PRAGMA user_version", e);
        }
    }
}
