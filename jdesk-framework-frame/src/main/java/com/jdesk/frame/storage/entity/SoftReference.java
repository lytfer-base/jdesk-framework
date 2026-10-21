package com.jdesk.frame.storage.entity;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Documents a weak relationship to another table/column without creating a
 * SQLite foreign key.
 *
 * <p>The metadata is reserved for later diagnostics and repair tools. It does
 * not change generated SQL in the first version.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD})
public @interface SoftReference {
    /**
     * Referenced table name.
     */
    String table();

    /**
     * Referenced column name.
     */
    String column();
}
