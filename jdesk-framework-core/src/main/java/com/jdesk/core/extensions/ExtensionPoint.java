package com.jdesk.core.extensions;

import java.util.List;

/**
 * Named extension point that collects extensions of one contract type, modeled on IntelliJ Platform's
 * {@code ExtensionPointName}. Plugins and host code register eager or lazy extensions with it.
 */
public interface ExtensionPoint<T> {

    /** Returns the unique name of the extension point. */
    String getName();

    /** Returns the contract type that extensions must implement or extend. */
    Class<T> getExtensionClass();

    /** Returns an immutable snapshot of all extensions; this may instantiate lazy extensions. */
    List<T> getExtensions();

    /** Registers an already created extension instance. */
    void registerExtension(T extension);

    /** Unregisters an extension instance registered with {@link #registerExtension}. */
    void unregisterExtension(T extension);

    /** Registers a lazy descriptor; its class doesn't load until the first {@link #getExtensions()}. */
    void registerLazy(ExtensionDescriptor<T> descriptor);

    /** Unregisters a lazy descriptor, or the instance it resolved to, matched by descriptor identity. */
    void unregisterLazy(ExtensionDescriptor<T> descriptor);

    /** Returns the number of registered entries. */
    int getExtensionCount();
}
