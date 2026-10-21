package com.jdesk.frame.storage.entity;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the single primary key column for an entity.
 *
 * <p>Exactly one {@code @Id} component is required. The generated schema creates
 * a primary key on this column but does not generate values for the caller.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD})
public @interface Id {
}
