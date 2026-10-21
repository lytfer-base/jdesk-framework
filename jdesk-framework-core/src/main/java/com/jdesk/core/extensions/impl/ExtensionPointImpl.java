package com.jdesk.core.extensions.impl;

import com.jdesk.core.extensions.ExtensionDescriptor;
import com.jdesk.core.extensions.ExtensionPoint;
import com.jdesk.core.extensions.ExtensionPointListener;
import com.jdesk.core.startup.StartupSpan;
import com.jdesk.core.startup.StartupTrace;
import com.jdesk.core.startup.StartupTraces;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Default {@link ExtensionPoint}. Plugin extensions are registered as {@link ExtensionDescriptor}s holding only a
 * class name and loader, and resolve on the first {@link #getExtensions()} or {@link #getExtensionList()}; instances
 * registered in code are kept eagerly, and both kinds stay in registration order.
 */
public class ExtensionPointImpl<T> implements ExtensionPoint<T> {

    private static final Logger LOG = LoggerFactory.getLogger(ExtensionPointImpl.class);

    private final String name;
    private final Class<T> extensionClass;

    /**
     * Eager instances and lazy descriptors in registration order; a resolved descriptor is replaced in place by its
     * instance.
     */
    private final List<Object> entries = new CopyOnWriteArrayList<>();

    /**
     * Resolved instances by descriptor. Must match descriptors by identity, not class name: the same implementation
     * class may be registered several times, and unregistering passes back the handle saved at registration.
     */
    private final Map<ExtensionDescriptor<T>, T> resolvedLazyInstances =
            Collections.synchronizedMap(new IdentityHashMap<>());

    private volatile boolean lazyResolved = false;

    private final List<ExtensionPointListener<T>> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean disposed = false;

    public ExtensionPointImpl(String name, Class<T> extensionClass) {
        this.name = name;
        this.extensionClass = extensionClass;
    }

    // ============== Accessors ==============

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Class<T> getExtensionClass() {
        return extensionClass;
    }

    // ============== Eager registration ==============

    @Override
    public void registerExtension(T extension) {
        checkNotDisposed();
        if (!extensionClass.isInstance(extension)) {
            throw new IllegalArgumentException(
                    "Extension " + extension.getClass().getName()
                    + " does not implement " + extensionClass.getName());
        }
        entries.add(extension);
        notifyListeners(extension, true);
    }

    /** Removes by identity; don't use {@code entries.remove}, which matches by equals and may remove another object. */
    @Override
    public void unregisterExtension(T extension) {
        checkNotDisposed();
        if (removeEntryByIdentity(extension)) {
            notifyListeners(extension, false);
        }
    }

    // ============== Lazy registration ==============

    /** Registers a descriptor whose class loads on the first {@link #getExtensions()} or {@link #getExtensionList}. */
    @Override
    public void registerLazy(ExtensionDescriptor<T> descriptor) {
        checkNotDisposed();
        if (lazyResolved) {
            // Past the resolution phase: instantiate now to keep the registration order.
            T instance = descriptor.createInstance();
            entries.add(instance);
            resolvedLazyInstances.put(descriptor, instance);
            notifyListeners(instance, true);
        } else {
            entries.add(descriptor);
        }
    }

    /**
     * Removes a pending descriptor, or the instance it resolved to, matched by descriptor identity; safe for a
     * descriptor that was never registered or failed to resolve.
     */
    @Override
    public void unregisterLazy(ExtensionDescriptor<T> descriptor) {
        checkNotDisposed();
        if (!lazyResolved) {
            removeEntryByIdentity(descriptor);
        } else {
            T instance = resolvedLazyInstances.remove(descriptor);
            if (instance != null && removeEntryByIdentity(instance)) {
                notifyListeners(instance, false);
            }
        }
    }

    /** Returns whether lazy descriptors are still waiting to be resolved. */
    public boolean hasPendingExtensions() {
        return !lazyResolved && entries.stream()
                .anyMatch(ExtensionDescriptor.class::isInstance);
    }

    // ============== Queries ==============

    @Override
    public List<T> getExtensions() {
        return getExtensionList();
    }

    /** Returns all extension instances, resolving the lazy descriptors on the first call. */
    public List<T> getExtensionList() {
        if (disposed) return Collections.emptyList();
        resolveLazy();
        List<T> result = new ArrayList<>();
        for (Object entry : entries) {
            if (extensionClass.isInstance(entry)) {
                result.add(extensionClass.cast(entry));
            }
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public int getExtensionCount() {
        return disposed ? 0 : entries.size();
    }

    // ============== Lazy resolution ==============

    /** Resolves all pending descriptors into instances; later calls don't instantiate anything again. */
    public void resolveLazy() {
        if (lazyResolved || !hasPendingExtensions()) {
            return;
        }

        synchronized (this) {
            if (lazyResolved) return;
            lazyResolved = true;

            StartupTrace startupTrace = StartupTraces.getCurrent();
            try (StartupSpan span = startupTrace.begin("extensionPoint.resolveLazy:" + name)) {
                span.detail("point", name);
                int resolvedCount = 0;
                for (Object entry : List.copyOf(entries)) {
                    if (!(entry instanceof ExtensionDescriptor<?> rawDescriptor)) {
                        continue;
                    }

                    @SuppressWarnings("unchecked")
                    ExtensionDescriptor<T> descriptor = (ExtensionDescriptor<T>) rawDescriptor;
                    try {
                        T instance = descriptor.createInstance();
                        int index = indexOfEntryByIdentity(descriptor);
                        if (index >= 0) {
                            entries.set(index, instance);
                            resolvedLazyInstances.put(descriptor, instance);
                        }
                        notifyListeners(instance, true);
                        resolvedCount++;
                        startupTrace.incrementCounter("extensions.lazyResolved");
                        LOG.debug("Resolved lazy extension: {} → {}",
                                descriptor.getImplementationClassName(), name);
                    } catch (Exception | LinkageError e) {
                        startupTrace.incrementCounter("extensions.resolveErrors");
                        LOG.error("Failed to resolve lazy extension {} for point {}",
                                descriptor.getImplementationClassName(), name, e);
                        removeEntryByIdentity(descriptor);
                    }
                }
                span.detail("resolved", resolvedCount);
                LOG.debug("Resolved {} lazy extensions for point '{}'",
                        resolvedCount, name);
            }
        }
    }

    // ============== Listeners ==============

    public void addListener(ExtensionPointListener<T> listener) {
        listeners.add(listener);
    }

    public void removeListener(ExtensionPointListener<T> listener) {
        listeners.remove(listener);
    }

    private void notifyListeners(T extension, boolean added) {
        for (ExtensionPointListener<T> listener : listeners) {
            try {
                if (added) {
                    listener.extensionAdded(extension);
                } else {
                    listener.extensionRemoved(extension);
                }
            } catch (Exception e) {
                // A failing listener must not break registration, but it must be logged.
                LOG.warn("Extension point '{}' listener {} threw on {}",
                        name, listener.getClass().getName(),
                        added ? "extensionAdded" : "extensionRemoved", e);
            }
        }
    }

    // ============== Lifecycle ==============

    public void dispose() {
        disposed = true;
        listeners.clear();
        entries.clear();
        resolvedLazyInstances.clear();
    }

    private void checkNotDisposed() {
        if (disposed) {
            throw new IllegalStateException("ExtensionPoint '" + name + "' is disposed");
        }
    }

    private int indexOfEntryByIdentity(Object target) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i) == target) {
                return i;
            }
        }
        return -1;
    }

    private boolean removeEntryByIdentity(Object target) {
        int index = indexOfEntryByIdentity(target);
        if (index < 0) {
            return false;
        }
        entries.remove(index);
        return true;
    }
}
