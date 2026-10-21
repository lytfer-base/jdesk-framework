package com.jdesk.frame.storage.dao;

/**
 * Converts values between Java domain types and SQLite-storable values.
 *
 * <p>The first SQLite DAO has built-in converters for common JDK types. This
 * public interface defines the extension point for the next step, where modules
 * will be able to register custom conversions without hand-writing mappers.
 */
public interface TypeConverter<J, D> {

    /**
     * Converts a Java value before it is bound to a SQL parameter.
     */
    D toDatabase(J value);

    /**
     * Converts a database value after it is read from a row.
     */
    J fromDatabase(D value);
}
