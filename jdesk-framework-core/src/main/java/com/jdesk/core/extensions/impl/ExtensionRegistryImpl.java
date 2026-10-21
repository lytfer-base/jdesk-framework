package com.jdesk.core.extensions.impl;

import com.jdesk.core.extensions.ExtensionDescriptor;
import com.jdesk.core.extensions.ExtensionPoint;
import com.jdesk.core.extensions.ExtensionPointListener;
import com.jdesk.core.extensions.ExtensionRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Default {@link ExtensionRegistry}. Reads are thread-safe; register extension points and extensions mostly during
 * startup. Extensions from {@link #registerLazy(String, ExtensionDescriptor)} instantiate only when their point is
 * first queried through {@link #getExtensions(String)} or {@link #forEachExtensionSafe}.
 */
public class ExtensionRegistryImpl implements ExtensionRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(ExtensionRegistryImpl.class);

    private final Map<String, ExtensionPointImpl<?>> extensionPoints = new ConcurrentHashMap<>();
    private volatile boolean initialized = false;
    private volatile boolean disposed = false;

    // ============== Extension points ==============

    @Override
    @SuppressWarnings("unchecked")
    public <T> ExtensionPoint<T> registerExtensionPoint(String name, Class<T> extensionClass) {
        checkNotDisposed();
        ExtensionPointImpl<T> point = new ExtensionPointImpl<>(name, extensionClass);
        ExtensionPointImpl<?> existing = extensionPoints.putIfAbsent(name, point);
        if (existing != null) {
            throw new IllegalStateException("Extension point already registered: " + name);
        }
        LOG.debug("Registered extension point: {}", name);
        return point;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> ExtensionPoint<T> getExtensionPoint(String name) {
        return (ExtensionPoint<T>) extensionPoints.get(name);
    }

    @Override
    public boolean unregisterExtensionPoint(String name) {
        checkNotDisposed();
        ExtensionPointImpl<?> point = extensionPoints.remove(name);
        if (point == null) {
            return false;
        }
        point.dispose();
        LOG.debug("Unregistered extension point: {}", name);
        return true;
    }

    // ============== Extensions ==============

    @Override
    @SuppressWarnings("unchecked")
    public <T> void registerExtension(String pointName, T extensionInstance) {
        checkNotDisposed();
        ExtensionPointImpl<T> point = getPointOrThrow(pointName);
        point.registerExtension(extensionInstance);
        LOG.debug("Registered extension '{}' to point '{}'",
                extensionInstance.getClass().getSimpleName(), pointName);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> void registerLazy(String pointName, ExtensionDescriptor<T> descriptor) {
        checkNotDisposed();
        ExtensionPointImpl<T> point = getPointOrThrow(pointName);
        point.registerLazy(descriptor);
        LOG.debug("Registered lazy extension {} → {}",
                descriptor.getImplementationClassName(), pointName);
    }

    // ============== Queries ==============

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> getExtensions(String pointName) {
        ExtensionPointImpl<T> point = (ExtensionPointImpl<T>) extensionPoints.get(pointName);
        if (point == null) {
            return Collections.emptyList();
        }
        return point.getExtensionList();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> void forEachExtensionSafe(String pointName, Class<T> extensionClass,
                                         Consumer<T> consumer) {
        ExtensionPointImpl<?> point = (ExtensionPointImpl<?>) extensionPoints.get(pointName);
        if (point == null) return;

        if (point.hasPendingExtensions()) {
            point.resolveLazy();
        }

        for (Object ext : point.getExtensionList()) {
            try {
                consumer.accept((T) ext);
            } catch (Exception e) {
                LOG.error("Extension threw exception in forEachExtensionSafe", e);
            }
        }
    }

    // ============== Listeners ==============

    @Override
    @SuppressWarnings("unchecked")
    public <T> void addExtensionPointListener(String pointName,
                                               ExtensionPointListener<T> listener) {
        ExtensionPointImpl<T> point = (ExtensionPointImpl<T>) extensionPoints.get(pointName);
        if (point != null) {
            point.addListener(listener);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> void removeExtensionPointListener(String pointName,
                                                  ExtensionPointListener<T> listener) {
        ExtensionPointImpl<T> point = (ExtensionPointImpl<T>) extensionPoints.get(pointName);
        if (point != null) {
            point.removeListener(listener);
        }
    }

    // ============== Lifecycle ==============

    public void initialize() {
        if (initialized) return;
        initialized = true;
        LOG.info("Extension registry initialized with {} extension points",
                extensionPoints.size());
    }

    @Override
    public void dispose() {
        disposed = true;
        extensionPoints.values().forEach(ExtensionPointImpl::dispose);
        extensionPoints.clear();
        LOG.info("Extension registry disposed");
    }

    // ============== Internals ==============

    @SuppressWarnings("unchecked")
    private <T> ExtensionPointImpl<T> getPointOrThrow(String pointName) {
        ExtensionPointImpl<T> point = (ExtensionPointImpl<T>) extensionPoints.get(pointName);
        if (point == null) {
            throw new IllegalArgumentException(
                    "Extension point not found: " + pointName
                    + ". Register the extension point before registering extensions.");
        }
        return point;
    }

    private void checkNotDisposed() {
        if (disposed) {
            throw new IllegalStateException("ExtensionRegistry is disposed");
        }
    }
}
