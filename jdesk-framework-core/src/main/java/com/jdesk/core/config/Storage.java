package com.jdesk.core.config;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Declares where a persistent component's state is stored, relative to the configuration directory or a path macro;
 * used inside {@link State}.
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface Storage {

    /** Path relative to the application configuration directory, or anchored with a {@link StoragePathMacros} macro. */
    String value() default "";

    /** A deprecated storage is only read as a migration source and never written. */
    boolean deprecated() default false;
}
