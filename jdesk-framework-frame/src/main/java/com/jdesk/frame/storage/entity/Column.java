package com.jdesk.frame.storage.entity;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Maps an entity record component to a SQLite column.
 *
 * <p>Field target is reserved for future class-based entities; the current
 * resolver reads record components only.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD})
public @interface Column {
    /**
     * SQLite column name.
     */
    String value();
}
