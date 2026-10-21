package com.jdesk.core.config;

/**
 * A component with persistent state, modeled on IntelliJ Platform's {@code PersistentStateComponent}. The state is
 * usually a plain POJO that {@link ComponentStore} loads at initialization and writes on shutdown or explicit saves.
 */
public interface PersistentStateComponent<T> {

    /** Returns the state to persist. */
    T getState();

    /** Loads previously persisted state; {@link ComponentStore} usually calls it during initialization. */
    void loadState(T state);

    /** Returns the initial state used when nothing is persisted yet. */
    default T getDefaultState() {
        return null;
    }

    /** Called when nothing is persisted and there is no default state. */
    default void noStateLoaded() {
    }
}
