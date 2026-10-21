package com.jdesk.frame.storage.entity;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Defines one SQLite index for an annotated entity table.
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface Index {
    /**
     * Stable index name.
     */
    String name();

    /**
     * Column names included in the index, in SQLite order.
     */
    String[] columns();
}
