package com.jdesk.frame.vfs;

import com.jdesk.core.app.PathManager;
import com.jdesk.core.disposer.Disposer;
import com.jdesk.core.vfs.*;
import com.jdesk.frame.storage.DatabaseDescriptor;
import com.jdesk.frame.storage.DatabaseOwner;
import com.jdesk.frame.storage.sqlite.SqliteDatabaseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises registered notifications against a real catalog, with no directory watcher or history side effects. */
class RegisteredVfsChangesTest {
    @TempDir Path home;
    private final SqliteDatabaseService databases = new SqliteDatabaseService();
    private final List<VfsScanResult> events = new ArrayList<>();
    private final List<Boolean> registered = new ArrayList<>();
    private final AtomicInteger historyLookups = new AtomicInteger();
    private DefaultManagedVfsService service;
    private VfsCatalog catalog;
    private VfsRootId rootId;
    private Path root;

    @BeforeEach void open() throws Exception {
        PathManager.setHomePathForTests(home);
        root = Files.createDirectory(home.resolve("documents"));
        service = new DefaultManagedVfsService((filter, listener) -> {
            throw new AssertionError("Explicit notifications must not start a watcher");
        }, (scope, change) -> { assertEquals("test.scope", scope); events.add(change); registered.add(false); }, scope -> {
            historyLookups.incrementAndGet(); return Optional.empty();
        }, (scope, change) -> { assertEquals("test.scope", scope); events.add(change); registered.add(true); });
        var database = databases.open(DatabaseDescriptor.builder("test.database")
                .owner(DatabaseOwner.application("test")).location(home.resolve("data")).build());
        catalog = service.openCatalog("test.scope", DatabaseVfsCatalogBackend.of(database), Locale.ROOT);
        rootId = catalog.registerRoot("root", "Documents", root);
    }

    @AfterEach void close() {
        service.dispose(); databases.dispose(); PathManager.resetForTests(); Disposer.clearAll();
    }

    @Test void anUnwatchedDeletionPublishesThePreservedOldRowWithoutRepeatingHistory() throws Exception {
        var old = file("old.md");
        Files.delete(old.absolutePath()); catalog.removeFile(old.fileId());
        var change = change(List.of(), List.of(), List.of(old));
        service.notifyRegisteredChanges("test.scope", change);
        service.notifyRegisteredChanges("test.scope", change);
        assertEquals(List.of(change, change), events);
        assertEquals(List.of(true, true), registered);
        assertEquals(old, events.get(0).missing().get(0));
        assertEquals(0, historyLookups.get());
        assertTrue(catalog.findFile(old.fileId()).isEmpty());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void aRealMoveRetainsTheIdAndCarriesBothPathsIncludingDescendants(boolean directory) throws Exception {
        List<VfsManagedFile> before;
        List<VfsManagedFile> after;
        if (directory) {
            Files.createDirectory(root.resolve("before"));
            var folder = catalog.registerFile(rootId, "before", true);
            var child = file("before/child.md");
            moveOnceReleased(root.resolve("before"), root.resolve("after"));
            before = List.of(folder, child);
            after = List.of(catalog.moveFile(folder.fileId(), rootId, "after"),
                    catalog.moveFile(child.fileId(), rootId, "after/child.md"));
        } else {
            var old = file("before.md");
            Files.move(old.absolutePath(), root.resolve("after.md"));
            before = List.of(old);
            after = List.of(catalog.moveFile(old.fileId(), rootId, "after.md"));
        }
        service.notifyRegisteredChanges("test.scope", change(after, List.of(), before));
        assertEquals(1, events.size());
        assertEquals(before, events.get(0).missing());
        assertEquals(after, events.get(0).added());
        for (int i = 0; i < before.size(); i++) {
            assertEquals(before.get(i).fileId(), after.get(i).fileId());
            assertNotEquals(before.get(i).relativePath(), after.get(i).relativePath());
            assertTrue(Files.exists(after.get(i).absolutePath()));
        }
        assertEquals(0, historyLookups.get());
    }

    @Test void addedAndUpdatedRowsMustMatchCurrentPresentRows() throws Exception {
        var row = file("edited.md");
        service.notifyRegisteredChanges("test.scope", change(List.of(row), List.of(), List.of()));
        Files.writeString(row.absolutePath(), "a longer saved body");
        var current = catalog.refreshFile(row.fileId());
        assertThrows(IllegalArgumentException.class, () -> service.notifyRegisteredChanges("test.scope",
                change(List.of(), List.of(row), List.of())));
        service.notifyRegisteredChanges("test.scope", change(List.of(), List.of(current), List.of()));
        assertEquals(2, events.size());
        assertEquals(current, events.get(1).updated().get(0));
        var missing = catalog.updateStatus(row.fileId(), VfsFileStatus.MISSING);
        assertThrows(IllegalArgumentException.class, () -> service.notifyRegisteredChanges("test.scope",
                change(List.of(), List.of(missing), List.of())));
        assertEquals(0, historyLookups.get());
    }

    @Test void aDeclaredDeletionCannotHideAStillPresentIdentity() throws Exception {
        var row = file("present.md");
        assertThrows(IllegalArgumentException.class, () -> service.notifyRegisteredChanges("test.scope",
                change(List.of(), List.of(), List.of(row))));
        assertThrows(IllegalArgumentException.class, () -> service.notifyRegisteredChanges("test.scope",
                change(List.of(row), List.of(), List.of(row))));
        assertTrue(events.isEmpty());
        assertEquals("body", Files.readString(row.absolutePath()));
    }

    @Test void aMissingCatalogRowCanBeReportedWithoutDeletingItAgain() throws Exception {
        var old = file("missing.md");
        Files.delete(old.absolutePath());
        var missing = catalog.updateStatus(old.fileId(), VfsFileStatus.MISSING);
        service.notifyRegisteredChanges("test.scope", change(List.of(), List.of(), List.of(old)));
        assertEquals(1, events.size());
        assertEquals(missing, catalog.findFile(old.fileId()).orElseThrow());
    }

    @Test void aRetainedMissingRowRejectsAnOldPathAfterAMoveAndDelete() throws Exception {
        var old = file("before.md");
        Files.move(old.absolutePath(), root.resolve("after.md"));
        var moved = catalog.moveFile(old.fileId(), rootId, "after.md");
        Files.delete(moved.absolutePath());
        catalog.updateStatus(old.fileId(), VfsFileStatus.MISSING);
        assertThrows(IllegalArgumentException.class, () -> service.notifyRegisteredChanges("test.scope",
                change(List.of(), List.of(), List.of(old))));
        assertTrue(events.isEmpty());
        service.notifyRegisteredChanges("test.scope", change(List.of(), List.of(), List.of(moved)));
        assertEquals(List.of(moved), events.get(0).missing());
    }

    @ParameterizedTest @ValueSource(strings = {"foreign-root", "foreign-path", "unknown-root"})
    void crossRootAndForgedPathsAreRejectedBeforePublishing(String mode) throws Exception {
        var row = file("file.md");
        Files.delete(row.absolutePath()); catalog.removeFile(row.fileId());
        Path otherRoot = Files.createDirectory(home.resolve("documents-other"));
        VfsRootId other = catalog.registerRoot("other", "Other", otherRoot);
        var wrong = new VfsManagedFile(row.fileId(), mode.equals("foreign-root") ? other : rootId,
                row.relativePath(), mode.equals("foreign-path") ? otherRoot.resolve("file.md") : row.absolutePath(),
                row.status(), row.directory(), row.size(), row.modifiedAt(), row.contentVersion(), row.fileCreatedAt(), row.readOnly());
        VfsRootId envelopeRoot = mode.equals("unknown-root") ? VfsRootId.of("unknown") : rootId;
        assertThrows(IllegalArgumentException.class, () -> service.notifyRegisteredChanges("test.scope",
                new VfsScanResult(envelopeRoot, List.of(), List.of(), List.of(wrong), 0)));
        assertTrue(events.isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"added", "updated", "missing", "contradiction"})
    void duplicateAndContradictoryIdentitiesAreRejected(String mode) throws Exception {
        var row = file("one.md");
        if (mode.equals("missing")) { Files.delete(row.absolutePath()); catalog.removeFile(row.fileId()); }
        var batch = switch (mode) {
            case "added" -> change(List.of(row, row), List.of(), List.of());
            case "updated" -> change(List.of(), List.of(row, row), List.of());
            case "missing" -> change(List.of(), List.of(), List.of(row, row));
            default -> change(List.of(row), List.of(row), List.of());
        };
        assertThrows(IllegalArgumentException.class, () -> service.notifyRegisteredChanges("test.scope", batch));
        assertTrue(events.isEmpty());
    }

    @Test void unopenedAndClosedCatalogsAreNotTreatedAsSuccessfulNotifications() throws Exception {
        var row = file("file.md");
        var batch = change(List.of(row), List.of(), List.of());
        assertThrows(IllegalStateException.class, () -> service.notifyRegisteredChanges("unknown", batch));
        databases.close("test.database");
        assertThrows(IllegalStateException.class, () -> service.notifyRegisteredChanges("test.scope", batch));
        assertTrue(events.isEmpty());
    }

    @Test void emptyNotificationsDoNothingAndLegacyUnwatchedNotificationsRemainSilent() throws Exception {
        var row = file("legacy.md");
        service.notifyRegisteredChanges("test.scope", change(List.of(), List.of(), List.of()));
        service.notifyChanged("test.scope", List.of(row.fileId()), List.of());
        assertTrue(events.isEmpty());
        assertEquals(0, historyLookups.get());
    }

    private VfsManagedFile file(String relative) throws Exception {
        Files.writeString(root.resolve(relative), "body");
        return catalog.registerFile(rootId, relative, false);
    }

    /** Renames a directory holding a fresh file; Windows briefly denies that rename after the file is written. */
    private static void moveOnceReleased(Path from, Path to) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            try {
                Files.move(from, to);
                return;
            } catch (AccessDeniedException held) {
                if (System.nanoTime() > deadline) {
                    throw held;
                }
                Thread.sleep(10);
            }
        }
    }

    private VfsScanResult change(List<VfsManagedFile> added, List<VfsManagedFile> updated, List<VfsManagedFile> missing) {
        return new VfsScanResult(rootId, added, updated, missing, 0);
    }
}
