package com.jdesk.core.message;

import com.jdesk.core.disposer.Disposable;

/**
 * Publish-subscribe message bus, the central event system of core, modeled on IntelliJ Platform's
 * {@code com.intellij.util.messages.MessageBus}. A topic is a plain interface that subscribers implement; publishers
 * call its methods on a dynamic proxy, which dispatches each call to every subscriber. Disposing the bus also disposes
 * the connections it created that have no other parent.
 */
public interface MessageBus extends Disposable {

    /** Creates a connection that is a child of this bus and is disposed with it. */
    MessageBusConnection connect();

    /**
     * Creates a connection disposed with {@code parentDisposable}; prefer it for UI components and other objects with
     * a clear lifecycle, so their subscriptions don't outlive them.
     */
    MessageBusConnection connect(Disposable parentDisposable);

    /** Returns a proxy for {@code topic} whose calls run every subscriber in turn on the calling thread. */
    <T> T syncPublisher(Class<T> topic);

    /** Returns a proxy for {@code topic} whose calls run the subscribers on a background thread. */
    <T> T asyncPublisher(Class<T> topic);

    /**
     * Rejects new connections while existing subscriptions and publishing keep working until disposal; used during
     * shutdown so services can still publish cleanup notices.
     */
    void closeForNewConnections();

    /** Returns whether the bus no longer accepts new connections. */
    boolean isClosedForNewConnections();

    /** Returns whether the bus is disposed, after which connecting and publishing throw IllegalStateException. */
    boolean isDisposed();
}
