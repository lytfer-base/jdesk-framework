package com.jdesk.core.message;

import com.jdesk.core.disposer.Disposable;

/**
 * Connection to a message bus; disposing it cancels the subscriptions made through it. As a {@link Disposable} it can
 * sit in the Disposer tree and be disposed with its parent.
 */
public interface MessageBusConnection extends Disposable {

    /** Subscribes {@code handler}, which must implement {@code topic}, to every message published on that topic. */
    <T> void subscribe(Class<T> topic, T handler);

    /** Removes a handler previously subscribed to {@code topic}. */
    <T> void unsubscribe(Class<T> topic, T handler);
}
