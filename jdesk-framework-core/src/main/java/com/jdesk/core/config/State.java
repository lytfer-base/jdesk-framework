package com.jdesk.core.config;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Marks a {@link PersistentStateComponent} as managed by {@link ComponentStore}, which reads its name and storages at
 * registration.
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface State {

    /** Stable component name, used as the persistence key. */
    String name();

    /** Where the component state is stored. */
    Storage[] storages() default {};
}
