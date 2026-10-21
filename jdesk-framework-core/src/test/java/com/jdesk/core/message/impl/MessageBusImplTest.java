package com.jdesk.core.message.impl;

import com.jdesk.core.disposer.Disposable;
import com.jdesk.core.disposer.Disposer;
import com.jdesk.core.message.MessageBusConnection;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MessageBusImplTest {

    @AfterEach
    void clearDisposerTree() {
        Disposer.clearAll();
    }

    @Test
    void disposerDisposeDisposesBusEvenWhenItHasNoConnections() {
        MessageBusImpl bus = new MessageBusImpl();

        Disposer.dispose(bus);

        assertTrue(bus.isDisposed());
        assertThrows(IllegalStateException.class, bus::connect);
    }

    @Test
    void disposerDisposeDisposesParentBoundConnectionsWhenBusWasNeverRegistered() {
        MessageBusImpl bus = new MessageBusImpl();
        Disposable parent = Disposer.newDisposable("parent");
        MessageBusConnection connection = bus.connect(parent);
        AtomicInteger calls = new AtomicInteger();

        connection.subscribe(TestTopic.class, () -> calls.incrementAndGet());
        bus.syncPublisher(TestTopic.class).event();

        Disposer.dispose(bus);

        assertEquals(1, calls.get());
        assertTrue(bus.isDisposed());
        assertThrows(IllegalStateException.class,
                () -> connection.subscribe(TestTopic.class, () -> calls.incrementAndGet()));
        assertEquals(1, Disposer.getTreeSize());
        assertDoesNotThrow(() -> Disposer.dispose(parent));
        assertEquals(0, Disposer.getTreeSize());
    }

    @Test
    void disposerDisposeDisposesArbitraryChildrenRegisteredUnderBus() {
        MessageBusImpl bus = new MessageBusImpl();
        CountingDisposable child = new CountingDisposable();

        Disposer.register(bus, child);
        Disposer.dispose(bus);

        assertTrue(bus.isDisposed());
        assertEquals(1, child.disposeCount);
        assertEquals(0, Disposer.getTreeSize());
    }

    @Test
    void closeForNewConnectionsRejectsConnectButAllowsPublishing() {
        MessageBusImpl bus = new MessageBusImpl();
        AtomicInteger calls = new AtomicInteger();
        bus.connect().subscribe(TestTopic.class, () -> calls.incrementAndGet());

        bus.closeForNewConnections();

        assertTrue(bus.isClosedForNewConnections());
        assertThrows(IllegalStateException.class, bus::connect);

        bus.syncPublisher(TestTopic.class).event();

        assertEquals(1, calls.get());
        assertFalse(bus.isDisposed());
    }

    @Test
    void publisherProxyRejectsDispatchAfterDispose() {
        MessageBusImpl bus = new MessageBusImpl();
        TestTopic syncPublisher = bus.syncPublisher(TestTopic.class);
        TestTopic asyncPublisher = bus.asyncPublisher(TestTopic.class);

        Disposer.dispose(bus);

        assertThrows(IllegalStateException.class, syncPublisher::event);
        assertThrows(IllegalStateException.class, asyncPublisher::event);
    }

    @Test
    void disposeShutsDownInjectedAsyncExecutor() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        MessageBusImpl bus = new MessageBusImpl(null, executor);

        try {
            assertFalse(executor.isShutdown());

            Disposer.dispose(bus);

            assertTrue(executor.isShutdown());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void asyncDispatchStopsBeforeNextSubscriberWhenBusIsDisposed() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        MessageBusImpl bus = new MessageBusImpl(null, executor);
        CountDownLatch firstSubscriberStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstSubscriber = new CountDownLatch(1);
        CountDownLatch firstSubscriberFinished = new CountDownLatch(1);
        AtomicInteger secondSubscriberCalls = new AtomicInteger();

        try {
            bus.connect().subscribe(BlockingTopic.class, () -> {
                firstSubscriberStarted.countDown();
                assertTrue(releaseFirstSubscriber.await(5, TimeUnit.SECONDS));
                firstSubscriberFinished.countDown();
            });
            bus.connect().subscribe(BlockingTopic.class, secondSubscriberCalls::incrementAndGet);

            bus.asyncPublisher(BlockingTopic.class).event();
            assertTrue(firstSubscriberStarted.await(5, TimeUnit.SECONDS));

            Disposer.dispose(bus);
            releaseFirstSubscriber.countDown();

            assertTrue(firstSubscriberFinished.await(5, TimeUnit.SECONDS));
            // Let the in-flight dispatch finish before checking whether the second subscriber was called.
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, secondSubscriberCalls.get());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * A subscriber that throws doesn't stop later subscribers of the same topic from getting the event, and the
     * publisher returns normally from a void method.
     */
    @Test
    void failingSubscriberDoesNotPreventOthersFromReceivingSyncEvent() {
        MessageBusImpl bus = new MessageBusImpl();
        AtomicInteger healthyCalls = new AtomicInteger();
        bus.connect().subscribe(TestTopic.class, () -> {
            throw new IllegalStateException("subscriber bug");
        });
        bus.connect().subscribe(TestTopic.class, healthyCalls::incrementAndGet);

        assertDoesNotThrow(() -> bus.syncPublisher(TestTopic.class).event());

        assertEquals(1, healthyCalls.get());
    }

    /**
     * On a boolean topic a subscriber that throws counts as a veto ({@code false}), so a single failure vetoes the
     * result even when the other subscribers return {@code true}.
     */
    @Test
    void failingSubscriberOnBooleanTopicIsTreatedAsVeto() {
        MessageBusImpl bus = new MessageBusImpl();
        bus.connect().subscribe(VetoTopic.class, () -> {
            throw new NullPointerException("subscriber bug");
        });
        bus.connect().subscribe(VetoTopic.class, () -> true);

        boolean result = bus.syncPublisher(VetoTopic.class).vote();

        assertFalse(result);
    }

    /**
     * A subscribe racing dispose leaves no subscription of the connection in topicSubscribers: it either succeeds and
     * is cleaned up by dispose or throws IllegalStateException, and never returns normally while the subscription
     * stays on the dead connection.
     */
    @Test
    void concurrentSubscribeDuringDisposeNeverLeavesOrphanSubscription() throws Exception {
        for (int round = 0; round < 300; round++) {
            MessageBusImpl bus = new MessageBusImpl();
            MessageBusConnection conn = bus.connect();
            AtomicReference<Throwable> subscribeFailure = new AtomicReference<>();
            CyclicBarrier barrier = new CyclicBarrier(2);
            Thread subscribeThread = new Thread(() -> {
                try {
                    barrier.await();
                    conn.subscribe(TestTopic.class, () -> { });
                } catch (Throwable t) {
                    subscribeFailure.set(t);
                }
            }, "m1-subscribe-" + round);
            subscribeThread.start();

            barrier.await();
            conn.dispose();
            subscribeThread.join(10_000);

            if (subscribeFailure.get() != null) {
                assertTrue(subscribeFailure.get() instanceof IllegalStateException,
                        "unexpected subscribe failure: " + subscribeFailure.get());
            }
            assertEquals(0, MessageBusImpl.countActiveSubscriptions(conn), "round " + round);
        }
    }

    /** Disposing a connection removes every subscription it registered from the bus's topicSubscribers. */
    @Test
    void disposeRemovesAllSubscriptionsRegisteredByConnection() {
        MessageBusImpl bus = new MessageBusImpl();
        MessageBusConnection conn = bus.connect();
        conn.subscribe(TestTopic.class, () -> { });
        conn.subscribe(VetoTopic.class, () -> true);

        assertEquals(2, MessageBusImpl.countActiveSubscriptions(conn));

        conn.dispose();

        assertEquals(0, MessageBusImpl.countActiveSubscriptions(conn));
    }

    private interface TestTopic {
        void event();
    }

    private interface VetoTopic {
        boolean vote();
    }

    private interface BlockingTopic {
        void event() throws InterruptedException;
    }

    private static final class CountingDisposable implements Disposable {
        private int disposeCount;

        @Override
        public void dispose() {
            disposeCount++;
        }
    }
}
