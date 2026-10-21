package com.jdesk.frame.storage;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Safe row accessor used by row mappers.
 *
 * <p>This wrapper hides {@link SQLException} from DAO code and centralizes
 * common conversions used by the first storage implementation.
 */
public final class DatabaseRow {

    private final ResultSet resultSet;

    /**
     * Wraps the active result set row. The wrapper does not own the result set.
     */
    public DatabaseRow(ResultSet resultSet) {
        this.resultSet = resultSet;
    }

    /**
     * Reads a nullable text column.
     */
    public String getString(String column) {
        try {
            return resultSet.getString(column);
        } catch (SQLException e) {
            throw new StorageException("Failed to read string column: " + column, e);
        }
    }

    /**
     * Reads an integer column.
     */
    public int getInt(String column) {
        try {
            return resultSet.getInt(column);
        } catch (SQLException e) {
            throw new StorageException("Failed to read int column: " + column, e);
        }
    }

    /**
     * Reads a long integer column.
     */
    public long getLong(String column) {
        try {
            return resultSet.getLong(column);
        } catch (SQLException e) {
            throw new StorageException("Failed to read long column: " + column, e);
        }
    }

    /**
     * Reads a floating-point column.
     */
    public double getDouble(String column) {
        try {
            return resultSet.getDouble(column);
        } catch (SQLException e) {
            throw new StorageException("Failed to read double column: " + column, e);
        }
    }

    /**
     * Reads a boolean stored as SQLite integer {@code 0} or {@code 1}.
     */
    public boolean getBoolean(String column) {
        return getInt(column) != 0;
    }

    /**
     * Reads a nullable BLOB column.
     */
    public byte[] getBytes(String column) {
        try {
            return resultSet.getBytes(column);
        } catch (SQLException e) {
            throw new StorageException("Failed to read bytes column: " + column, e);
        }
    }

    /**
     * Reads a path stored as text.
     */
    public Path getPath(String column) {
        String value = getString(column);
        return value == null ? null : Path.of(value);
    }

    /**
     * Reads an instant stored as epoch milliseconds.
     */
    public Instant getInstant(String column) {
        long value = getLong(column);
        return Instant.ofEpochMilli(value);
    }

    /**
     * Reads a UTC local date-time stored as epoch milliseconds.
     */
    public LocalDateTime getLocalDateTime(String column) {
        return LocalDateTime.ofInstant(getInstant(column), ZoneOffset.UTC);
    }
}
