package com.jdesk.core.message.impl;

import com.jdesk.core.app.Application;
import com.jdesk.core.app.ApplicationManager;
import com.jdesk.core.disposer.Disposable;
import com.jdesk.core.disposer.Disposer;
import com.jdesk.core.message.MessageBus;
import com.jdesk.core.message.MessageBusKey;
import com.jdesk.core.message.MessageBusManager;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Default {@link MessageBusManager}: caches the created {@link MessageBusImpl}s by {@link MessageBusKey} and registers
 * each one under the lifecycle owner it was given; a disposed bus removes itself from the cache.
 */
public final class MessageBusManagerImpl implements MessageBusManager, Disposable {

    private final ConcurrentMap<MessageBusKey, BusEntry> buses = new ConcurrentHashMap<>();
    private volatile boolean disposed;

    @Override
    public MessageBus getOrCreateBus(MessageBusKey key, Disposable lifecycleOwner) {
        return getOrCreateBus(key, lifecycleOwner, null);
    }

    @Override
    public MessageBus getOrCreateBus(MessageBusKey key, Disposable lifecycleOwner, MessageBus parentBus) {
        checkNotDisposed();
        Objects.requireNonNull(key, "key");
        checkLifecycleOwner(key, lifecycleOwner);

        BusEntry existing = buses.get(key);
        if (existing != null && !existing.bus().isDisposed()) {
            checkSameLifecycleOwner(key, existing.lifecycleOwner(), lifecycleOwner);
            return existing.bus();
        }
        if (existing != null) {
            buses.remove(key, existing);
        }

        BusEntry entry = buses.compute(key, (busKey, current) -> {
            if (current != null && !current.bus().isDisposed()) {
                return current;
            }
            checkApplicationCanCreateBus();
            MessageBusImpl bus = new MessageBusImpl(() -> removeDisposedBus(busKey));
            if (lifecycleOwner != null) {
                Disposer.register(lifecycleOwner, bus);
            }
            return new BusEntry(bus, lifecycleOwner, parentBus);
        });
        return entry.bus();
    }

    @Override
    public MessageBus getBusIfCreated(MessageBusKey key) {
        checkNotDisposed();
        Objects.requireNonNull(key, "key");
        BusEntry entry = buses.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.bus().isDisposed()) {
            buses.remove(key, entry);
            return null;
        }
        return entry.bus();
    }

    @Override
    public void dispose() {
        disposed = true;
        buses.clear();
    }

    private void checkNotDisposed() {
        if (disposed) {
            throw new IllegalStateException("MessageBusManager is disposed");
        }
    }

    private void checkApplicationCanCreateBus() {
        Application app = ApplicationManager.getApplicationIfCreated();
        if (app == null) {
            return;
        }
        Application.State state = app.getState();
        if (state == Application.State.SHUTTING_DOWN || state == Application.State.DISPOSED) {
            throw new IllegalStateException(
                    "Application is shutting down or disposed - cannot create MessageBus");
        }
    }

    private void checkLifecycleOwner(MessageBusKey key, Disposable lifecycleOwner) {
        if (lifecycleOwner == null && !MessageBusKey.application().equals(key)) {
            throw new IllegalArgumentException(
                    "lifecycleOwner is required for non-application message bus: " + key);
        }
    }

    private void checkSameLifecycleOwner(MessageBusKey key,
                                         Disposable existingOwner,
                                         Disposable requestedOwner) {
        if (existingOwner != requestedOwner) {
            throw new IllegalStateException(
                    "Message bus already exists with a different lifecycle owner: " + key);
        }
    }

    private void removeDisposedBus(MessageBusKey key) {
        BusEntry entry = buses.get(key);
        if (entry != null && entry.bus().isDisposed()) {
            buses.remove(key, entry);
        }
    }

    private record BusEntry(MessageBus bus, Disposable lifecycleOwner, MessageBus parentBus) {
    }
}
