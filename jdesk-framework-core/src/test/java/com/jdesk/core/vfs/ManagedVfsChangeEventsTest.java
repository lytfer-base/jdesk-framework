package com.jdesk.core.vfs;

import com.jdesk.core.app.ApplicationManager;
import com.jdesk.core.app.PathManager;
import com.jdesk.core.app.TestApplication;
import com.jdesk.core.disposer.Disposable;
import com.jdesk.core.disposer.Disposer;
import com.jdesk.core.message.MessageBus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedVfsChangeEventsTest {

    @TempDir
    Path homeDir;

    private TestApplication app;

    /** Starts a test application and returns its bus, the one the publisher looks up through ApplicationManager. */
    private MessageBus startApp() {
        PathManager.setHomePathForTests(homeDir);
        app = new TestApplication();
        app.init();
        return app.getMessageBus();
    }

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.shutdown();
            app = null;
        }
        Disposer.clearAll();
        PathManager.resetForTests();
    }

    @Test
    void publishDeliversOneBatchEventPerRefresh() {
        MessageBus bus = startApp();
        List<ManagedVfsChangeEvent> received = new ArrayList<>();
        bus.connect().subscribe(ManagedVfsChangeListener.class, received::add);

        VfsManagedFile added = managed("a.md", VfsFileStatus.PRESENT);
        VfsManagedFile updated = managed("b.md", VfsFileStatus.PRESENT);
        VfsManagedFile missing = managed("c.md", VfsFileStatus.MISSING);
        VfsScanResult diff = new VfsScanResult(VfsRootId.of("r1"),
                List.of(added), List.of(updated), List.of(missing), 7);

        new ManagedVfsChangePublisher().publish("lib", diff);

        assertEquals(1, received.size());
        ManagedVfsChangeEvent event = received.get(0);
        assertEquals("lib", event.scopeId());
        assertEquals(List.of(added), event.changes().added());
        assertEquals(List.of(updated), event.changes().updated());
        assertEquals(List.of(missing), event.changes().missing());
    }

    @Test
    void publishSkipsEmptyBatch() {
        MessageBus bus = startApp();
        List<ManagedVfsChangeEvent> received = new ArrayList<>();
        bus.connect().subscribe(ManagedVfsChangeListener.class, received::add);

        VfsScanResult empty = new VfsScanResult(VfsRootId.of("r1"), List.of(), List.of(), List.of(), 5);
        new ManagedVfsChangePublisher().publish("lib", empty);

        assertTrue(received.isEmpty());
    }

    @Test
    void publishIsNoOpWhenNoApplicationInstalled() {
        // With no application, getApplicationIfCreated() is null and publishing is silently skipped.
        assertNull(ApplicationManager.getApplicationIfCreated());
        assertDoesNotThrow(() -> new ManagedVfsChangePublisher().publish("lib", oneChange()));
    }

    @Test
    void disposingParentStopsDelivery() {
        MessageBus bus = startApp();
        List<ManagedVfsChangeEvent> received = new ArrayList<>();
        Disposable parent = Disposer.newDisposable("test-parent");
        bus.connect(parent).subscribe(ManagedVfsChangeListener.class, received::add);
        ManagedVfsChangePublisher publisher = new ManagedVfsChangePublisher();

        publisher.publish("lib", oneChange());
        assertEquals(1, received.size());

        Disposer.dispose(parent);
        publisher.publish("lib", oneChange());
        assertEquals(1, received.size()); // Nothing is delivered after unsubscribing.
    }

    private static VfsScanResult oneChange() {
        return new VfsScanResult(VfsRootId.of("r1"),
                List.of(managed("a.md", VfsFileStatus.PRESENT)), List.of(), List.of(), 0);
    }

    private static VfsManagedFile managed(String relativePath, VfsFileStatus status) {
        return new VfsManagedFile(VfsFileId.of("id-" + relativePath), VfsRootId.of("r1"),
                relativePath, Path.of("C:/lib/" + relativePath), status, false, 10, 0, 1, 0);
    }
}
