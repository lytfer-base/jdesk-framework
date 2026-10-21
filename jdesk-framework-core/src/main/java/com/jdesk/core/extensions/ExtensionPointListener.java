package com.jdesk.core.extensions;

/**
 * Listener for changes to the extensions of an extension point.
 */
public interface ExtensionPointListener<T> {

    /** Called after an extension instance is registered. */
    default void extensionAdded(T extension) {}

    /** Called after an extension instance is removed. */
    default void extensionRemoved(T extension) {}
}
