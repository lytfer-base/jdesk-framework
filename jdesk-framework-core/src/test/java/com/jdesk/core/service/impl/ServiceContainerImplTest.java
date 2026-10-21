package com.jdesk.core.service.impl;

import com.jdesk.core.disposer.Disposable;
import com.jdesk.core.disposer.Disposer;
import com.jdesk.core.service.ServiceDescriptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ServiceContainerImplTest {

    @AfterEach
    void clearDisposerTree() {
        Disposer.clearAll();
        NoArgService.disposeCount = 0;
        ConcurrentLazyService.creationCount.set(0);
    }

    @Test
    void disposableServiceIsDisposedThroughDisposerTree() {
        ServiceContainerImpl container = new ServiceContainerImpl();
        List<String> events = new ArrayList<>();
        DisposableService service = new DisposableService(events);
        Disposable child = () -> events.add("child");

        container.registerInstance(DisposableService.class, service);
        Disposer.register(service, child);

        container.dispose();

        assertEquals(List.of("child", "service"), events);
    }

    /** A service throwing an Error on dispose doesn't stop the later ones; the container still finishes disposing. */
    @Test
    void aServiceFailingWithAnErrorOnDisposeDoesNotStopTheOthers() {
        ServiceContainerImpl container = new ServiceContainerImpl();
        List<String> events = new ArrayList<>();
        container.registerInstance(DisposableService.class, new DisposableService(events));
        container.registerInstance(ErrorOnDisposeService.class, new ErrorOnDisposeService());

        container.dispose();

        assertEquals(List.of("service"), events);
    }

    @Test
    void sameDisposableInstanceRegisteredAsMultipleServicesIsDisposedOnce() {
        ServiceContainerImpl container = new ServiceContainerImpl();
        MultiService service = new MultiService();

        container.registerInstance(FirstService.class, service);
        container.registerInstance(SecondService.class, service);

        container.dispose();

        assertEquals(1, service.disposeCount);
        assertEquals(0, Disposer.getTreeSize());
    }

    @Test
    void unregisterDisposableServiceDisposesThroughDisposerTree() {
        ServiceContainerImpl container = new ServiceContainerImpl();
        List<String> events = new ArrayList<>();
        DisposableService service = new DisposableService(events);
        Disposable child = () -> events.add("child");

        container.registerInstance(DisposableService.class, service);
        Disposer.register(service, child);

        assertTrue(container.unregisterService(DisposableService.class));

        assertEquals(List.of("child", "service"), events);
        assertFalse(container.hasService(DisposableService.class));
        assertNull(container.getServiceIfCreated(DisposableService.class));
        assertThrows(IllegalStateException.class,
                () -> container.getService(DisposableService.class));
    }

    @Test
    void unregisterOneAliasDoesNotDisposeSharedServiceInstance() {
        ServiceContainerImpl container = new ServiceContainerImpl();
        MultiService service = new MultiService();

        container.registerInstance(FirstService.class, service);
        container.registerInstance(SecondService.class, service);

        assertTrue(container.unregisterService(FirstService.class));
        assertEquals(0, service.disposeCount);
        assertTrue(container.hasService(SecondService.class));

        assertTrue(container.unregisterService(SecondService.class));
        assertEquals(1, service.disposeCount);
    }

    @Test
    void unregisterLazyServiceBeforeCreationOnlyRemovesDescriptor() {
        ServiceContainerImpl container = new ServiceContainerImpl();

        container.registerService(NoArgService.class,
                ServiceDescriptor.ofClass(NoArgService.class, NoArgService.class));

        assertTrue(container.unregisterService(NoArgService.class));

        assertFalse(container.hasService(NoArgService.class));
        assertNull(container.getServiceIfCreated(NoArgService.class));
        assertEquals(0, NoArgService.disposeCount);
    }

    @Test
    @SuppressWarnings("resource")
    void concurrentLazyServiceIsCreatedOnlyOnce() throws Exception {
        ServiceContainerImpl container = new ServiceContainerImpl();
        container.registerService(ConcurrentLazyService.class,
                ServiceDescriptor.ofClass(ConcurrentLazyService.class, ConcurrentLazyService.class));

        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ConcurrentLazyService>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return container.getService(ConcurrentLazyService.class);
                }));
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            ConcurrentLazyService first = futures.get(0).get(5, TimeUnit.SECONDS);
            for (Future<ConcurrentLazyService> future : futures) {
                assertSame(first, future.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, ConcurrentLazyService.creationCount.get());
        } finally {
            executor.shutdownNow();
        }
    }

    interface FirstService {
    }

    interface SecondService {
    }

    static final class DisposableService implements Disposable {
        private final List<String> events;

        DisposableService(List<String> events) {
            this.events = events;
        }

        @Override
        public void dispose() {
            events.add("service");
        }
    }

    static final class ErrorOnDisposeService implements Disposable {
        @Override
        public void dispose() {
            throw new AssertionError("dispose failed");
        }
    }

    static final class MultiService implements FirstService, SecondService, Disposable {
        private int disposeCount;

        @Override
        public void dispose() {
            disposeCount++;
        }
    }

    static final class NoArgService implements Disposable {
        private static int disposeCount;

        @Override
        public void dispose() {
            disposeCount++;
        }
    }

    static final class ConcurrentLazyService {
        private static final AtomicInteger creationCount = new AtomicInteger();

        ConcurrentLazyService() {
            creationCount.incrementAndGet();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
    }
}
