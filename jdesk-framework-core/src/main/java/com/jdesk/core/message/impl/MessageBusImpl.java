package com.jdesk.core.message.impl;

import com.jdesk.core.disposer.Disposable;
import com.jdesk.core.disposer.Disposer;
import com.jdesk.core.message.MessageBus;
import com.jdesk.core.message.MessageBusConnection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Default {@link MessageBus}: a dynamic proxy dispatches each topic method call to every subscriber, synchronously on
 * the calling thread or asynchronously on a background pool. Connections from {@link #connect()} are children of the
 * bus in the {@link Disposer} tree and are disposed before it; connections from {@link #connect(Disposable)} follow
 * the given parent, which suits UI components and other objects with a clear lifecycle.
 */
public class MessageBusImpl implements MessageBus {

    private static final Logger LOG = LoggerFactory.getLogger(MessageBusImpl.class);
    private static final int DEFAULT_ASYNC_THREAD_COUNT =
            Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
    private static final int DEFAULT_ASYNC_QUEUE_CAPACITY = 1024;
    private static final AtomicLong ASYNC_THREAD_COUNTER = new AtomicLong();

    private final Map<Class<?>, List<Subscription<?>>> topicSubscribers = new ConcurrentHashMap<>();

    private final Set<ConnectionImpl> connections = ConcurrentHashMap.newKeySet();

    private final ExecutorService asyncExecutor;

    private final AtomicLong connectionCounter = new AtomicLong();

    private final Runnable disposeCallback;

    private volatile boolean disposed = false;
    private volatile boolean closedForNewConnections = false;

    public MessageBusImpl() {
        this(null);
    }

    public MessageBusImpl(Runnable disposeCallback) {
        this(disposeCallback, createDefaultAsyncExecutor());
    }

    MessageBusImpl(Runnable disposeCallback, ExecutorService asyncExecutor) {
        this.disposeCallback = disposeCallback;
        this.asyncExecutor = Objects.requireNonNull(asyncExecutor, "asyncExecutor");
    }

    private static ExecutorService createDefaultAsyncExecutor() {
        return new ThreadPoolExecutor(
                DEFAULT_ASYNC_THREAD_COUNT,
                DEFAULT_ASYNC_THREAD_COUNT,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(DEFAULT_ASYNC_QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "MessageBus-Async-" + ASYNC_THREAD_COUNTER.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    // ============== Connections ==============

    /** Creates a connection that lives as long as this bus. */
    @Override
    public MessageBusConnection connect() {
        checkCanConnect();
        ConnectionImpl conn = new ConnectionImpl("conn-" + connectionCounter.incrementAndGet());
        connections.add(conn);

        Disposer.register(this, conn);
        return conn;
    }

    /** Creates a connection disposed with {@code parentDisposable}; {@code null} acts like {@link #connect()}. */
    @Override
    public MessageBusConnection connect(Disposable parentDisposable) {
        checkCanConnect();
        if (parentDisposable == null) {
            return connect();
        }
        ConnectionImpl conn = new ConnectionImpl("conn-" + connectionCounter.incrementAndGet());
        connections.add(conn);

        Disposer.register(parentDisposable, conn);
        return conn;
    }

    // ============== Publishing ==============

    /** Returns a publisher whose calls notify the subscribers one by one on the calling thread. */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T syncPublisher(Class<T> topic) {
        return createPublisher(topic, false);
    }

    /** Returns a publisher whose calls notify the subscribers on a background pool. */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T asyncPublisher(Class<T> topic) {
        return createPublisher(topic, true);
    }

    @SuppressWarnings("unchecked")
    private <T> T createPublisher(Class<T> topic, boolean async) {
        checkNotDisposed();
        return (T) Proxy.newProxyInstance(
                topic.getClassLoader(),
                new Class<?>[]{topic},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return handleObjectMethod(proxy, method, args);
                    }
                    return dispatchToSubscribers(topic, method, args, async);
                });
    }

    private Object dispatchToSubscribers(Class<?> topic, java.lang.reflect.Method method, Object[] args, boolean async) {
        checkNotDisposed();
        List<Subscription<?>> subscribers = topicSubscribers.getOrDefault(topic, Collections.emptyList());

        if (async) {
            try {
                asyncExecutor.submit(() -> dispatchAsync(topic, method, args, subscribers));
            } catch (RejectedExecutionException e) {
                if (disposed) {
                    throw new IllegalStateException("MessageBus is disposed", e);
                }
                throw e;
            }
            return defaultReturnValue(method.getReturnType());
        } else {
            // Boolean results are ANDed and a failing handler counts as false; other results keep the last value.
            Class<?> returnType = method.getReturnType();
            boolean booleanReturn = returnType == boolean.class || returnType == Boolean.class;
            Object result = booleanReturn ? Boolean.TRUE : defaultReturnValue(returnType);

            for (Subscription<?> sub : subscribers) {
                if (disposed) {
                    break;
                }
                if (sub.connection.isConnectionDisposed()) {
                    continue;
                }
                try {
                    Object value = method.invoke(sub.handler, args);
                    if (returnType != Void.TYPE) {
                        if (booleanReturn) {
                            if (Boolean.FALSE.equals(value)) {
                                result = Boolean.FALSE;
                            }
                        } else {
                            result = value;
                        }
                    }
                } catch (Exception e) {
                    Throwable cause = unwrapInvocationTarget(e);
                    LOG.error("Sync dispatch error for topic {} handler {}: {}",
                            topic.getName(), sub.handler.getClass().getName(),
                            booleanReturn ? "handler failure is treated as veto (false)" : "skipping failed handler",
                            cause);
                    if (booleanReturn) {
                        result = Boolean.FALSE;
                    }
                }
            }
            return result;
        }
    }

    private void dispatchAsync(Class<?> topic, java.lang.reflect.Method method,
                               Object[] args, List<Subscription<?>> subscribers) {
        if (disposed) {
            return;
        }
        for (Subscription<?> sub : subscribers) {
            if (disposed) {
                break;
            }
            if (sub.connection.isConnectionDisposed()) {
                continue;
            }
            try {
                method.invoke(sub.handler, args);
            } catch (Exception e) {
                LOG.error("Async dispatch error for topic {} handler {}",
                        topic.getName(), sub.handler.getClass().getName(), unwrapInvocationTarget(e));
            }
        }
    }

    private static Throwable unwrapInvocationTarget(Exception e) {
        return e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null
                ? e.getCause()
                : e;
    }

    /** Counts the connection's registered subscriptions; nonzero after dispose means an orphaned one. Tests only. */
    static int countActiveSubscriptions(MessageBusConnection connection) {
        if (!(connection instanceof ConnectionImpl conn)) {
            throw new IllegalArgumentException("Not a connection created by MessageBusImpl");
        }
        return conn.countActiveSubscriptions();
    }

    /** Handles the {@link Object} methods of the proxy, which must not be dispatched as topic events. */
    private static Object handleObjectMethod(Object proxy, java.lang.reflect.Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "MessageBusPublisher@" + System.identityHashCode(proxy);
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> null;
        };
    }

    /** Returns the value for async calls and topics without subscribers: true for boolean, zero or null otherwise. */
    private static Object defaultReturnValue(Class<?> returnType) {
        if (returnType == Void.TYPE) return null;
        if (returnType == boolean.class || returnType == Boolean.class) return Boolean.TRUE;
        if (!returnType.isPrimitive()) return null;
        if (returnType == char.class) return '\0';
        if (returnType == byte.class) return (byte) 0;
        if (returnType == short.class) return (short) 0;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        if (returnType == float.class) return 0f;
        if (returnType == double.class) return 0d;
        return null;
    }

    // ============== Lifecycle ==============

    @Override
    public boolean isDisposed() {
        return disposed;
    }

    @Override
    public void closeForNewConnections() {
        checkNotDisposed();
        closedForNewConnections = true;
    }

    @Override
    public boolean isClosedForNewConnections() {
        return disposed || closedForNewConnections;
    }

    /**
     * Disposes the bus and its remaining connections; idempotent. Release it with {@code Disposer.dispose(bus)}, which
     * disposes the child connections first and removes the bus from the active tree.
     */
    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;

        // Fallback for connections the Disposer tree hasn't disposed yet.
        for (ConnectionImpl conn : new ArrayList<>(connections)) {
            try {
                Disposer.dispose(conn);
            } catch (Exception e) {
                LOG.warn("Error disposing connection: {}", conn.name, e);
            }
        }
        connections.clear();
        topicSubscribers.clear();

        asyncExecutor.shutdown();

        if (disposeCallback != null) {
            try {
                disposeCallback.run();
            } catch (Exception e) {
                LOG.warn("Error during MessageBus dispose callback", e);
            }
        }

        LOG.info("MessageBus disposed");
    }

    private void checkNotDisposed() {
        if (disposed) {
            throw new IllegalStateException("MessageBus is disposed");
        }
    }

    private void checkCanConnect() {
        checkNotDisposed();
        if (closedForNewConnections) {
            throw new IllegalStateException("MessageBus is closed for new connections");
        }
    }

    // ============== Subscription ==============

    private static class Subscription<T> {
        final T handler;
        final long subscriptionId;
        final ConnectionImpl connection;

        Subscription(T handler, ConnectionImpl connection, long subscriptionId) {
            this.handler = handler;
            this.connection = connection;
            this.subscriptionId = subscriptionId;
        }
    }

    // ============== Connection ==============

    private class ConnectionImpl implements MessageBusConnection {
        private final String name;
        private final Map<Class<?>, List<Long>> topicToSubscriptionIds = new ConcurrentHashMap<>();
        private final AtomicLong subIdCounter = new AtomicLong();
        private volatile boolean connDisposed = false;
        /** Serializes subscribe and unsubscribe with dispose, so no subscription slips in after disposal. */
        private final Object registrationLock = new Object();

        ConnectionImpl(String name) {
            this.name = name;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> void subscribe(Class<T> topic, T handler) {
            checkNotDisposed();
            if (!topic.isInstance(handler)) {
                throw new IllegalArgumentException(
                        "Handler " + handler.getClass().getName()
                                + " does not implement topic " + topic.getName());
            }

            long subId = subIdCounter.incrementAndGet();
            Subscription<T> sub = new Subscription<>(handler, this, subId);

            // Re-check under the lock: a concurrent dispose either wins (this throws) or removes this subscription.
            synchronized (registrationLock) {
                if (connDisposed) {
                    throw new IllegalStateException("Connection '" + name + "' is disposed");
                }
                topicSubscribers.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(sub);
                topicToSubscriptionIds.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(subId);
            }

            LOG.debug("[{}] Subscribed to topic: {}", name, topic.getSimpleName());
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> void unsubscribe(Class<T> topic, T handler) {
            synchronized (registrationLock) {
                List<Subscription<?>> subs = topicSubscribers.get(topic);
                if (subs != null) {
                    subs.removeIf(s -> s.handler == handler && s.connection == this);
                }
            }
        }

        /** Disposes the connection and removes all its subscriptions. */
        @Override
        public void dispose() {
            synchronized (registrationLock) {
                if (connDisposed) return;
                connDisposed = true;

                for (Map.Entry<Class<?>, List<Long>> entry : topicToSubscriptionIds.entrySet()) {
                    Class<?> topic = entry.getKey();
                    List<Subscription<?>> subs = topicSubscribers.get(topic);
                    if (subs != null) {
                        subs.removeIf(s -> s.connection == this);
                    }
                }
                topicToSubscriptionIds.clear();
                connections.remove(this);
            }
            LOG.debug("[{}] Connection disposed", name);
        }

        private void checkNotDisposed() {
            if (connDisposed) {
                throw new IllegalStateException("Connection '" + name + "' is disposed");
            }
        }

        private boolean isConnectionDisposed() {
            return connDisposed;
        }

        int countActiveSubscriptions() {
            int count = 0;
            for (List<Subscription<?>> subs : topicSubscribers.values()) {
                for (Subscription<?> sub : subs) {
                    if (sub.connection == this) {
                        count++;
                    }
                }
            }
            return count;
        }

        @Override
        public String toString() {
            return "MessageBusConnection{" + name + "}";
        }
    }
}
