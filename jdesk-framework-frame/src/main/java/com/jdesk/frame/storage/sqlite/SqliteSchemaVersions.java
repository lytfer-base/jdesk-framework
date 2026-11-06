package com.jdesk.frame.storage.sqlite;

import com.jdesk.frame.storage.StorageException;
import org.sqlite.SQLiteConfig;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the per-namespace versions in {@code jdesk_schema_version} and compares them with the versions a program
 * declares. Reading a file uses a read-only connection, so it can run before anything opens the database for writing.
 */
public final class SqliteSchemaVersions {

    private static final int READ_BUSY_TIMEOUT_MILLIS = 2000;

    private SqliteSchemaVersions() {
    }

    /** One namespace whose stored version is higher than the version the program declares. */
    public record TooNew(String namespace, int databaseVersion, int supportedVersion) {
    }

    /**
     * Returns every namespace's stored version, counting the {@code -wal} file; an empty map, with nothing created,
     * when the file is missing or has no version table. Throws {@link StorageException} on an unreadable file.
     */
    public static Map<String, Integer> read(Path database) {
        if (!Files.isRegularFile(database)) {
            return Map.of();
        }
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        config.setBusyTimeout(READ_BUSY_TIMEOUT_MILLIS);
        // Don't add immutable=1: SQLite would ignore the -wal file and report a crashed database as older than it is.
        String url = "jdbc:sqlite:" + sqliteAddress(database.toUri());
        try (Connection connection = DriverManager.getConnection(url, config.toProperties())) {
            return read(connection);
        } catch (SQLException e) {
            throw new StorageException("Failed to read schema versions: " + database, e);
        }
    }

    /** Returns the SQLite address of a file URI, moving a UNC host into the path: SQLite rejects a URI authority. */
    static String sqliteAddress(URI uri) {
        String authority = uri.getRawAuthority();
        return authority == null ? uri.toASCIIString() : "file:////" + authority + uri.getRawPath();
    }

    /**
     * Returns the stored version of every namespace on an open connection, or an empty map without a version table.
     */
    static Map<String, Integer> read(Connection connection) throws SQLException {
        Map<String, Integer> versions = new HashMap<>();
        try (Statement statement = connection.createStatement()) {
            try (ResultSet table = statement.executeQuery(
                    "select 1 from sqlite_master where type = 'table' and name = 'jdesk_schema_version'")) {
                if (!table.next()) {
                    return Map.of();
                }
            }
            // Must skip NULL: SQLite allows it in a text primary key, and Map.copyOf throws on a null key.
            try (ResultSet rows = statement.executeQuery(
                    "select namespace, version from jdesk_schema_version where namespace is not null")) {
                while (rows.next()) {
                    versions.put(rows.getString("namespace"), rows.getInt("version"));
                }
            }
        }
        return Map.copyOf(versions);
    }

    /**
     * Returns the first declared namespace whose stored version is higher, in {@code declared}'s iteration order.
     * Namespaces missing from {@code declared}, or declared as 0 (no migrations, so no claim), are not compared.
     */
    public static Optional<TooNew> findTooNew(Map<String, Integer> stored, Map<String, Integer> declared) {
        for (Map.Entry<String, Integer> entry : declared.entrySet()) {
            Integer version = stored.get(entry.getKey());
            if (version != null && entry.getValue() > 0 && version > entry.getValue()) {
                return Optional.of(new TooNew(entry.getKey(), version, entry.getValue()));
            }
        }
        return Optional.empty();
    }
}
