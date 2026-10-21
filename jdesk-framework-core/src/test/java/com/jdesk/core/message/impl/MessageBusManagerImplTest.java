package com.jdesk.core.message.impl;

import com.jdesk.core.disposer.Disposable;
import com.jdesk.core.disposer.Disposer;
import com.jdesk.core.message.MessageBus;
import com.jdesk.core.message.MessageBusKey;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MessageBusManagerImplTest {

    @AfterEach
    void clearDisposerTree() {
        Disposer.clearAll();
    }

    @Test
    void sameKeyReturnsSameBusAndDifferentKeysGetDifferentBuses() {
        MessageBusManagerImpl manager = new MessageBusManagerImpl();
        MessageBusKey projectKey = MessageBusKey.project("demo");
        MessageBusKey serviceKey = MessageBusKey.service(TestService.class);
        Disposable projectOwner = Disposer.newDisposable("project");
        Disposable serviceOwner = Disposer.newDisposable("service");

        MessageBus projectBus = manager.getOrCreateBus(projectKey, projectOwner);
        MessageBus sameProjectBus = manager.getOrCreateBus(projectKey, projectOwner);
        MessageBus serviceBus = manager.getOrCreateBus(serviceKey, serviceOwner);

        assertSame(projectBus, sameProjectBus);
        assertNotSame(projectBus, serviceBus);
    }

    @Test
    void lifecycleOwnerDisposalDisposesBusAndRemovesKeyReference() {
        MessageBusManagerImpl manager = new MessageBusManagerImpl();
        MessageBusKey projectKey = MessageBusKey.project("demo");
        Disposable projectOwner = Disposer.newDisposable("project");

        MessageBus projectBus = manager.getOrCreateBus(projectKey, projectOwner);

        Disposer.dispose(projectOwner);

        assertTrue(projectBus.isDisposed());
        assertNull(manager.getBusIfCreated(projectKey));
    }

    @Test
    void applicationBusIsCachedByApplicationKey() {
        MessageBusManagerImpl manager = new MessageBusManagerImpl();

        MessageBus appBus = manager.getOrCreateBus(MessageBusKey.application(), null);

        assertSame(appBus, manager.getOrCreateBus(MessageBusKey.application(), null));
        assertSame(appBus, manager.getBusIfCreated(MessageBusKey.application()));

        Disposer.dispose(appBus);
        assertNull(manager.getBusIfCreated(MessageBusKey.application()));
    }

    @Test
    void keysWithSameScopeAndIdResolveToSameBus() {
        MessageBusManagerImpl manager = new MessageBusManagerImpl();
        Disposable owner = Disposer.newDisposable("owner");

        MessageBusKey first = MessageBusKey.project("demo");
        MessageBusKey second = MessageBusKey.of("project", "demo");

        MessageBus bus = manager.getOrCreateBus(first, owner);

        assertEquals(first, second);
        assertSame(bus, manager.getBusIfCreated(second));
    }

    @Test
    void nonApplicationBusRequiresLifecycleOwner() {
        MessageBusManagerImpl manager = new MessageBusManagerImpl();

        assertThrows(IllegalArgumentException.class,
                () -> manager.getOrCreateBus(MessageBusKey.project("demo"), null));
    }

    @Test
    void sameKeyCannotBeReboundToDifferentLifecycleOwner() {
        MessageBusManagerImpl manager = new MessageBusManagerImpl();
        MessageBusKey key = MessageBusKey.project("demo");
        Disposable firstOwner = Disposer.newDisposable("first");
        Disposable secondOwner = Disposer.newDisposable("second");

        manager.getOrCreateBus(key, firstOwner);

        assertThrows(IllegalStateException.class,
                () -> manager.getOrCreateBus(key, secondOwner));
    }

    private static final class TestService {
    }
}
