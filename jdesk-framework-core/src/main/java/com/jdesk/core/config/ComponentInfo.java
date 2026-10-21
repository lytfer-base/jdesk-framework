package com.jdesk.core.config;

import java.util.List;

/**
 * Immutable metadata of a registered persistent component: its name, state type, instance and storages in declaration
 * order, as {@link ComponentStore} uses them to load and save.
 */
final class ComponentInfo<T> {

    /** Usually {@link State#name()} or the fully qualified name of the state class. */
    private final String name;

    private final PersistentStateComponent<T> component;

    private final Class<T> stateClass;

    private final List<StorageSpec> storages;

    /** Creates the metadata; {@code name} must be unique in the store. */
    ComponentInfo(String name,
                  PersistentStateComponent<T> component,
                  Class<T> stateClass,
                  List<StorageSpec> storages) {
        this.name = name;
        this.component = component;
        this.stateClass = stateClass;
        this.storages = List.copyOf(storages);
    }

    String name() {
        return name;
    }

    PersistentStateComponent<T> component() {
        return component;
    }

    Class<T> stateClass() {
        return stateClass;
    }

    List<StorageSpec> storages() {
        return storages;
    }

    /** One storage of a component; a deprecated one is only read for old state and never written. */
    record StorageSpec(String path, boolean deprecated) {
    }
}
