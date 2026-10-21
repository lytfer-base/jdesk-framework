package com.jdesk.frame.storage.sqlite;

import com.jdesk.frame.storage.DatabaseRow;
import com.jdesk.frame.storage.DatabaseSession;
import com.jdesk.frame.storage.StorageException;
import com.jdesk.frame.storage.dao.RowMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite implementation of the scoped database session.
 *
 * <p>The session wraps one JDBC connection supplied by a handle operation. It
 * owns statements and result sets created during calls, but it does not own the
 * connection itself.
 */
class SqliteDatabaseSession implements DatabaseSession {

    private final Connection connection;

    /**
     * Wraps the active operation connection.
     */
    SqliteDatabaseSession(Connection connection) {
        this.connection = connection;
    }

    /**
     * Executes an update/DDL statement using prepared-statement parameter
     * binding.
     */
    @Override
    public int update(String sql, Object... params) {
        if (SqlStatements.hasMoreThanOne(sql)) {
            throw new StorageException("update runs a single statement; split it into separate calls: " + sql);
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("SQL update failed: " + sql, e);
        }
    }

    /**
     * Executes a query and maps each result row through the supplied mapper.
     */
    @Override
    public <T> List<T> queryList(String sql, RowMapper<T> mapper, Object... params) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<T> results = new ArrayList<>();
                DatabaseRow row = new DatabaseRow(resultSet);
                while (resultSet.next()) {
                    results.add(mapper.map(row));
                }
                return results;
            }
        } catch (SQLException e) {
            throw new StorageException("SQL query failed: " + sql, e);
        }
    }

    /**
     * Exposes the underlying connection for advanced migration or integration
     * work.
     */
    @Override
    public <T> T unwrap(Class<T> type) {
        if (type.isInstance(connection)) {
            return type.cast(connection);
        }
        throw new StorageException("Cannot unwrap database session to: " + type.getName());
    }

    /**
     * Binds common Java values to SQLite-friendly statement parameters.
     */
    private static void bind(PreparedStatement statement, Object... params) throws SQLException {
        if (params == null) {
            return;
        }
        for (int i = 0; i < params.length; i++) {
            Object value = params[i];
            if (value instanceof Boolean bool) {
                statement.setInt(i + 1, bool ? 1 : 0);
            } else if (value instanceof Enum<?> enumValue) {
                statement.setString(i + 1, enumValue.name());
            } else if (value instanceof java.nio.file.Path path) {
                statement.setString(i + 1, path.toString());
            } else if (value instanceof Instant instant) {
                statement.setLong(i + 1, instant.toEpochMilli());
            } else if (value instanceof LocalDateTime localDateTime) {
                statement.setLong(i + 1, localDateTime.toInstant(ZoneOffset.UTC).toEpochMilli());
            } else {
                statement.setObject(i + 1, value);
            }
        }
    }
}
