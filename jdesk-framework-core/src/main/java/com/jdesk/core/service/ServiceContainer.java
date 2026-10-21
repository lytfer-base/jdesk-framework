package com.jdesk.core.service;

import java.util.Collection;

/**
 * Application-level service container, modeled on IntelliJ Platform's {@code ComponentManager}. Services are
 * registered by interface type, either as instances or as {@link ServiceDescriptor}s created lazily on first access.
 */
public interface ServiceContainer {

    /**
     * Returns the service for {@code serviceClass}, creating it on first access if it was registered by descriptor.
     *
     * @throws IllegalStateException if no such service is registered
     */
    <T> T getService(Class<T> serviceClass);

    /** Returns the service if it was already created, or {@code null}; never creates it. */
    <T> T getServiceIfCreated(Class<T> serviceClass);

    /** Returns a snapshot of the created services; must not create lazy services, so persistence code can scan it. */
    Collection<Object> getCreatedServicesSnapshot();

    /** Registers an already created instance, which lookups then return. */
    <T> void registerInstance(Class<T> serviceClass, T instance);

    /** Registers a descriptor; the service is created on the first {@link #getService(Class)}. */
    <T> void registerService(Class<T> serviceClass, ServiceDescriptor<T> descriptor);

    /** Unregisters the service, disposing its instance if one was created; returns whether anything was removed. */
    <T> boolean unregisterService(Class<T> serviceClass);

    /** Returns whether the service is registered, whether or not it was created. */
    <T> boolean hasService(Class<T> serviceClass);

    /** Disposes all services in reverse registration order. */
    void dispose();
}
