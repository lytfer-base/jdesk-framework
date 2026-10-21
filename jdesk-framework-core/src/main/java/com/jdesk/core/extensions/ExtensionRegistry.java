package com.jdesk.core.extensions;

import java.util.List;
import java.util.function.Consumer;

/**
 * Central registry of extension points, modeled on IntelliJ Platform's {@code ExtensionsArea}. Plugins and the host
 * application contribute behavior through extension points; each point has a name and a contract type, and
 * extensions are registered and queried by that name.
 */
public interface ExtensionRegistry {

    /**
     * Registers and returns an extension point with a unique {@code name}, such as {@code deskframe.toolWindow}, whose
     * extensions must be instances of {@code extensionClass}.
     */
    <T> ExtensionPoint<T> registerExtensionPoint(String name, Class<T> extensionClass);

    /** Returns the extension point named {@code name}, or {@code null} if none is registered. */
    <T> ExtensionPoint<T> getExtensionPoint(String name);

    /** Unregisters and disposes an extension point; returns whether one was removed. */
    boolean unregisterExtensionPoint(String name);

    /**
     * Registers an extension instance eagerly; for lazy loading use {@link #registerLazy(String, ExtensionDescriptor)}.
     */
    <T> void registerExtension(String pointName, T extensionInstance);

    /**
     * Registers a lazy descriptor whose class loads on the first {@link #getExtensions(String)} or
     * {@link #forEachExtensionSafe} of the point. Plugins should contribute this way, so startup loads none of their
     * classes.
     */
    <T> void registerLazy(String pointName, ExtensionDescriptor<T> descriptor);

    /** Returns an unmodifiable list of all extensions of the point. */
    <T> List<T> getExtensions(String pointName);

    /**
     * Passes every extension of the point to {@code consumer}; one that throws doesn't stop the rest.
     * {@code extensionClass} only drives type inference and must match the registered type.
     */
    <T> void forEachExtensionSafe(String pointName, Class<T> extensionClass, Consumer<T> consumer);

    /** Adds a listener called when extensions of the point are added or removed. */
    <T> void addExtensionPointListener(String pointName, ExtensionPointListener<T> listener);

    /** Removes an extension point listener. */
    <T> void removeExtensionPointListener(String pointName, ExtensionPointListener<T> listener);

    /** Disposes every extension point and clears the registry. */
    void dispose();
}
