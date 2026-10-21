package com.jdesk.frame.storage;

import com.jdesk.frame.storage.dao.RowMapper;

import java.util.List;
import java.util.Optional;

/**
 * Database access valid only inside the callback that receives it: binds parameters, maps results and normalizes
 * exceptions, while the handle keeps control of the underlying connection.
 */
public interface DatabaseSession {

    /**
     * Executes one INSERT, UPDATE, DELETE or DDL statement with bound parameters and returns the affected row count.
     * Pass one statement per call: SQL holding several throws {@link StorageException}; a CREATE TRIGGER counts as one.
     */
    int update(String sql, Object... params);

    /**
     * Executes a query and maps each row with the provided mapper.
     */
    <T> List<T> queryList(String sql, RowMapper<T> mapper, Object... params);

    /**
     * Executes a query and returns the first mapped row, if any.
     *
     * <p>This helper intentionally does not fail when multiple rows are
     * returned; callers should add a SQL limit when uniqueness matters.
     */
    default <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        List<T> results = queryList(sql, mapper, params);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /** Returns the backend object behind this session, for migrations and backend work the API can't express. */
    <T> T unwrap(Class<T> type);
}
