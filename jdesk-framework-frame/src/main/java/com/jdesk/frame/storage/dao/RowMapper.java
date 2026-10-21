package com.jdesk.frame.storage.dao;

import com.jdesk.frame.storage.DatabaseRow;

/**
 * Maps the current database row to a Java object.
 *
 * <p>The mapper is called while the underlying result set is positioned on one
 * valid row. Implementations should read values through {@link DatabaseRow}
 * rather than storing the row for later use.
 */
@FunctionalInterface
public interface RowMapper<T> {

    /**
     * Converts the current row into one mapped result.
     */
    T map(DatabaseRow row);
}
