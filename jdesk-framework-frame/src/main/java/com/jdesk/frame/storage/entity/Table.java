package com.jdesk.frame.storage.entity;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a Java record as a storage entity and provides its SQLite table name.
 *
 * <p>The first storage implementation supports record entities explicitly
 * registered in a {@code DatabaseDescriptor}; it does not scan the classpath.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Table {
    /**
     * SQLite table name used for generated schema and CRUD SQL.
     */
    String value();

    /**
     * Index definitions that should be created with the table.
     */
    Index[] indexes() default {};
}
