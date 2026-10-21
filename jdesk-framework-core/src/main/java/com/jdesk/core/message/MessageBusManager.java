package com.jdesk.core.message;

import com.jdesk.core.disposer.Disposable;

/**
 * Application-level registry and factory of message buses by {@link MessageBusKey}, so callers can find the same bus
 * without holding its lifecycle owner. The manager doesn't own the buses: creating any bus except the application bus
 * requires the parent {@link Disposable} that owns it.
 */
public interface MessageBusManager {

    /**
     * Returns the bus for {@code key}, creating it under {@code lifecycleOwner} on first access; the owner is required
     * for every bus except the application bus.
     */
    MessageBus getOrCreateBus(MessageBusKey key, Disposable lifecycleOwner);

    /**
     * Like {@link #getOrCreateBus(MessageBusKey, Disposable)}; {@code parentBus} is only recorded, and messages don't
     * propagate between buses.
     */
    MessageBus getOrCreateBus(MessageBusKey key, Disposable lifecycleOwner, MessageBus parentBus);

    /** Returns the bus already created for {@code key}, or {@code null}. */
    MessageBus getBusIfCreated(MessageBusKey key);
}
