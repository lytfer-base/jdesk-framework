package com.jdesk.frame.vfs;

import com.jdesk.core.app.PathManager;
import com.jdesk.core.disposer.Disposer;
import com.jdesk.core.vfs.ScanFilter;
import com.jdesk.core.vfs.VfsCatalog;
import com.jdesk.core.vfs.VfsFileId;
import com.jdesk.core.vfs.VfsFileStatus;
import com.jdesk.core.vfs.VfsManagedFile;
import com.jdesk.core.vfs.VfsRootId;
import com.jdesk.core.vfs.VfsScanResult;
import com.jdesk.core.vfs.VfsWatchListener;
import com.jdesk.core.vfs.VfsWatcher;
import com.jdesk.core.vfs.VfsWatcherFactory;
import com.jdesk.frame.storage.DatabaseDescriptor;
import com.jdesk.frame.storage.DatabaseHandle;
import com.jdesk.frame.storage.DatabaseOwner;
import com.jdesk.frame.storage.sqlite.SqliteDatabaseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DefaultManagedVfsServiceWatchTest {

    /** Home for the databases (the PathManager system directory), kept apart from the watched roots. */
    @TempDir
    Path homeDir;

    private final SqliteDatabaseService dbService = new SqliteDatabaseService();

    @AfterEach
    void tearDown() {
        dbService.dispose();
        Disposer.clearAll();
        PathManager.resetForTests();
    }

    private static final class CapturingFactory implements VfsWatcherFactory {
        VfsWatchListener listener;
        ScanFilter filter;
        final AtomicBoolean disposed = new AtomicBoolean(false);
        final AtomicReference<Set<Path>> roots = new AtomicReference<>(Set.of());
        /** How many watchers were created, which shows whether the watcher is a global singleton. */
        final AtomicInteger created = new AtomicInteger();

        @Override
        public VfsWatcher create(ScanFilter filter, VfsWatchListener listener) {
            created.incrementAndGet();
            this.filter = filter;
            this.listener = listener;
            return new VfsWatcher() {
                @Override
                public void setRoots(Set<Path> newRoots) {
                    roots.set(newRoots);
                }

                @Override
                public void dispose() {
                    disposed.set(true);
                }
            };
        }
    }

    @Test
    void onChangeRefreshesAffectedDirAndPublishesMergedDiff(@TempDir Path root) throws Exception {
        DatabaseHandle db = openVfsDb("scope.test");
        CapturingFactory factory = new CapturingFactory();
        AtomicReference<String> publishedScope = new AtomicReference<>();
        AtomicReference<VfsScanResult> publishedDiff = new AtomicReference<>();
        DefaultManagedVfsService service = new DefaultManagedVfsService(factory, (scope, diff) -> {
            publishedScope.set(scope);
            publishedDiff.set(diff);
        });
        VfsCatalog catalog = service.openCatalog("scope.test", DatabaseVfsCatalogBackend.of(db));
        VfsRootId rootId = catalog.registerRoot("proj", "proj", root);
        Files.createDirectories(root.resolve("docs"));
        catalog.scanRoot(rootId, (p, d) -> true);

        service.watch("scope.test", rootId, root, (p, d) -> true);
        assertNotNull(factory.listener, "watch should create a watcher through the factory and attach the listener");

        Files.writeString(root.resolve("docs").resolve("a.md"), "hi");
        factory.listener.changed(Set.of(root.resolve("docs")));

        assertEquals("scope.test", publishedScope.get());
        VfsScanResult diff = publishedDiff.get();
        assertNotNull(diff, "onChange should refresh and publish the merged diff");
        List<String> addedPaths = diff.added().stream().map(VfsManagedFile::relativePath).toList();
        assertTrue(addedPaths.contains("docs/a.md"), "a new file should be in diff.added, got: " + addedPaths);

        service.dispose();
    }

    /**
     * After the app writes and registers a file itself, the watcher's reconcile <b>reports no change</b> because the
     * snapshot already matches the disk, so the writer must notify subscribers explicitly with notifyChanged.
     */
    @Test
    void notifyChangedPublishesWhatTheWriterRegisteredItself(@TempDir Path root) throws Exception {
        DatabaseHandle db = openVfsDb("scope.write");
        CapturingFactory factory = new CapturingFactory();
        AtomicReference<VfsScanResult> published = new AtomicReference<>();
        DefaultManagedVfsService service = new DefaultManagedVfsService(factory,
                (scope, diff) -> published.set(diff));
        VfsCatalog catalog = service.openCatalog("scope.write", DatabaseVfsCatalogBackend.of(db));
        VfsRootId rootId = catalog.registerRoot("proj", "proj", root);
        Files.createDirectories(root.resolve("inbox"));
        catalog.scanRoot(rootId, (p, d) -> true);
        service.watch("scope.write", rootId, root, (p, d) -> true);

        // Write and self-register (registerFile on create, refreshFile on write); neither step publishes an event.
        Path landing = root.resolve("inbox").resolve("archived.md");
        Files.writeString(landing, "");
        VfsManagedFile row = catalog.registerFile(rootId, "inbox/archived.md", false);
        Files.writeString(landing, "# 归档正文");
        catalog.refreshFile(row.fileId());

        // First the cause: the watcher's reconcile reports nothing because the snapshot already matches the disk.
        published.set(null);
        factory.listener.changed(Set.of(root.resolve("inbox")));
        VfsScanResult viaWatcher = published.get();
        assertNotNull(viaWatcher);
        assertTrue(viaWatcher.added().isEmpty() && viaWatcher.updated().isEmpty()
                        && viaWatcher.missing().isEmpty(),
                "the writer registered itself, so the watcher's reconcile reports no change");

        // Subscribers get the change only when the writer notifies explicitly.
        published.set(null);
        service.notifyChanged("scope.write", List.of(row.fileId()), List.of());

        VfsScanResult diff = published.get();
        assertNotNull(diff, "notifyChanged must publish one event");
        assertEquals(List.of("inbox/archived.md"),
                diff.added().stream().map(VfsManagedFile::relativePath).toList(),
                "the new file must be in added, so subscribers refresh its folder");

        service.dispose();
    }

    /** For a scope not being watched, notifyChanged does nothing: no exception and no event. */
    @Test
    void notifyChangedIsANoOpForUnwatchedScope(@TempDir Path root) throws Exception {
        DatabaseHandle db = openVfsDb("scope.silent");
        CapturingFactory factory = new CapturingFactory();
        AtomicReference<VfsScanResult> published = new AtomicReference<>();
        DefaultManagedVfsService service = new DefaultManagedVfsService(factory,
                (scope, diff) -> published.set(diff));
        VfsCatalog catalog = service.openCatalog("scope.silent", DatabaseVfsCatalogBackend.of(db));
        VfsRootId rootId = catalog.registerRoot("proj", "proj", root);
        Files.writeString(root.resolve("a.md"), "x");
        VfsManagedFile row = catalog.registerFile(rootId, "a.md", false);

        service.notifyChanged("scope.silent", List.of(row.fileId()), List.of());

        assertNull(published.get(), "nothing watches this scope, so there is no subscriber to notify");
        service.dispose();
    }

    /**
     * Events still reach the right scope when a sibling root's name extends another's as a string. A floorEntry
     * lookup would pick {@code alphabet} for {@code alpha\docs}, fail startsWith and silently drop the event.
     */
    @Test
    void dispatchesToRootWhoseSiblingIsAStringPrefix(@TempDir Path base) throws Exception {
        Path alpha = Files.createDirectories(base.resolve("alpha"));
        Path alphabet = Files.createDirectories(base.resolve("alphabet"));
        DatabaseHandle dbAlpha = openVfsDb("scope.alpha");
        DatabaseHandle dbAlphabet = openVfsDb("scope.alphabet");
        CapturingFactory factory = new CapturingFactory();
        AtomicReference<String> publishedScope = new AtomicReference<>();
        DefaultManagedVfsService service = new DefaultManagedVfsService(factory,
                (scope, diff) -> publishedScope.set(scope));
        VfsCatalog catalogAlpha = service.openCatalog("scope.alpha", DatabaseVfsCatalogBackend.of(dbAlpha));
        VfsCatalog catalogAlphabet = service.openCatalog("scope.alphabet", DatabaseVfsCatalogBackend.of(dbAlphabet));
        VfsRootId rootIdAlpha = catalogAlpha.registerRoot("alpha", "alpha", alpha);
        service.watch("scope.alpha", rootIdAlpha, alpha, (p, d) -> true);
        service.watch("scope.alphabet", catalogAlphabet.registerRoot("alphabet", "alphabet", alphabet), alphabet, (p, d) -> true);

        Path docs = Files.createDirectories(alpha.resolve("docs"));
        assertTrue(factory.filter.include(docs.resolve("a.md"), false),
                "the combined filter must not block alpha's events because a sibling root's name extends it");

        factory.listener.changed(Set.of(docs));
        assertEquals("scope.alpha", publishedScope.get(), "alpha's change should publish to scope.alpha");

        service.dispose();
    }

    @Test
    void unwatchDisposesWatcher(@TempDir Path root) throws Exception {
        DatabaseHandle db = openVfsDb("scope.uw");
        CapturingFactory factory = new CapturingFactory();
        DefaultManagedVfsService service = new DefaultManagedVfsService(factory, (scope, diff) -> { });
        VfsCatalog catalog = service.openCatalog("scope.uw", DatabaseVfsCatalogBackend.of(db));
        VfsRootId rootId = catalog.registerRoot("proj", "proj", root);

        service.watch("scope.uw", rootId, root, (p, d) -> true);
        service.unwatch("scope.uw");

        assertTrue(factory.disposed.get(), "unwatch should dispose the scope's watcher");
        service.dispose();
    }

    @Test
    void twoScopesShareOneWatcherAndDispatchToTheirOwnCatalog(@TempDir Path base) throws Exception {
        // Two scopes share one watcher, and events are dispatched by root to each scope's catalog.
        Path rootA = Files.createDirectories(base.resolve("projA"));
        Path rootB = Files.createDirectories(base.resolve("projB"));
        DatabaseHandle dbA = openVfsDb("scope.a");
        DatabaseHandle dbB = openVfsDb("scope.b");
        CapturingFactory factory = new CapturingFactory();
        Map<String, VfsScanResult> published = new ConcurrentHashMap<>();
        DefaultManagedVfsService service = new DefaultManagedVfsService(factory, published::put);

        VfsCatalog catalogA = service.openCatalog("scope.a", DatabaseVfsCatalogBackend.of(dbA));
        VfsCatalog catalogB = service.openCatalog("scope.b", DatabaseVfsCatalogBackend.of(dbB));
        VfsRootId rootIdA = catalogA.registerRoot("a", "a", rootA);
        VfsRootId rootIdB = catalogB.registerRoot("b", "b", rootB);
        catalogA.scanRoot(rootIdA, (p, d) -> true);
        catalogB.scanRoot(rootIdB, (p, d) -> true);

        service.watch("scope.a", rootIdA, rootA, (p, d) -> true);
        service.watch("scope.b", rootIdB, rootB, (p, d) -> true);

        assertEquals(1, factory.created.get(), "two scopes should share one watcher, not create one each");
        assertEquals(Set.of(rootA.toAbsolutePath().normalize(), rootB.toAbsolutePath().normalize()),
                factory.roots.get(), "the watcher's roots should include both scopes' roots");

        Files.writeString(rootB.resolve("b.md"), "y");
        factory.listener.changed(Set.of(rootB));   // Only B changes.

        assertTrue(published.containsKey("scope.b"), "should publish to scope.b");
        assertFalse(published.containsKey("scope.a"), "A didn't change, so nothing should publish to scope.a");
        assertTrue(published.get("scope.b").added().stream()
                        .anyMatch(f -> f.relativePath().equals("b.md")),
                "B's new file should be reconciled through B's own catalog");
        service.dispose();
    }

    @Test
    void unwatchOneScopeKeepsWatchingTheOther(@TempDir Path base) throws Exception {
        // Unwatching one scope removes just its root and keeps the shared watcher for the other.
        Path rootA = Files.createDirectories(base.resolve("projA"));
        Path rootB = Files.createDirectories(base.resolve("projB"));
        DatabaseHandle dbA = openVfsDb("scope.a");
        DatabaseHandle dbB = openVfsDb("scope.b");
        CapturingFactory factory = new CapturingFactory();
        DefaultManagedVfsService service = new DefaultManagedVfsService(factory, (scope, diff) -> { });
        VfsCatalog catalogA = service.openCatalog("scope.a", DatabaseVfsCatalogBackend.of(dbA));
        VfsCatalog catalogB = service.openCatalog("scope.b", DatabaseVfsCatalogBackend.of(dbB));
        service.watch("scope.a", catalogA.registerRoot("a", "a", rootA), rootA, (p, d) -> true);
        service.watch("scope.b", catalogB.registerRoot("b", "b", rootB), rootB, (p, d) -> true);

        service.unwatch("scope.a");

        assertEquals(Set.of(rootB.toAbsolutePath().normalize()), factory.roots.get(), "only B should stay watched");
        assertFalse(factory.disposed.get(), "a scope is still watched, so the shared watcher must stay");

        service.unwatch("scope.b");
        assertTrue(factory.disposed.get(), "the watcher and its threads go once the last scope unwatches");
        service.dispose();
    }

    @Test
    void rootLevelChangeRefreshesNonRecursively(@TempDir Path root) throws Exception {
        // A root-level change reconciles only the root's direct children, not the whole tree.
        Files.createDirectories(root.resolve("deep").resolve("nested"));
        Files.writeString(root.resolve("deep").resolve("nested").resolve("x.md"), "deep");
        DatabaseHandle db = openVfsDb("scope.root");
        CapturingFactory factory = new CapturingFactory();
        Map<String, VfsScanResult> published = new ConcurrentHashMap<>();
        DefaultManagedVfsService service = new DefaultManagedVfsService(factory, published::put);
        VfsCatalog catalog = service.openCatalog("scope.root", DatabaseVfsCatalogBackend.of(db));
        VfsRootId rootId = catalog.registerRoot("proj", "proj", root);
        service.watch("scope.root", rootId, root, (p, d) -> true);

        Files.writeString(root.resolve("PROJECT.md"), "# P");
        factory.listener.changed(Set.of(root));   // A root-level change: the affected directory is the root.

        VfsScanResult diff = published.get("scope.root");
        assertNotNull(diff);
        List<String> added = diff.added().stream().map(VfsManagedFile::relativePath).toList();
        assertTrue(added.contains("PROJECT.md"), "a new root-level file should be reconciled, got: " + added);
        assertFalse(added.contains("deep/nested/x.md"), "non-recursive: the deep subtree must not be scanned");
        service.dispose();
    }

    @Test
    void overflowRefreshesWholeTreeRecursively(@TempDir Path root) throws Exception {
        // Overflow falls back to a recursive rescan of the whole tree, unlike a non-recursive root-level change.
        Files.createDirectories(root.resolve("deep").resolve("nested"));
        DatabaseHandle db = openVfsDb("scope.of");
        CapturingFactory factory = new CapturingFactory();
        Map<String, VfsScanResult> published = new ConcurrentHashMap<>();
        DefaultManagedVfsService service = new DefaultManagedVfsService(factory, published::put);
        VfsCatalog catalog = service.openCatalog("scope.of", DatabaseVfsCatalogBackend.of(db));
        VfsRootId rootId = catalog.registerRoot("proj", "proj", root);
        service.watch("scope.of", rootId, root, (p, d) -> true);

        Files.writeString(root.resolve("deep").resolve("nested").resolve("x.md"), "deep");
        factory.listener.overflow(Set.of(root.toAbsolutePath().normalize()));

        VfsScanResult diff = published.get("scope.of");
        assertNotNull(diff);
        List<String> added = diff.added().stream().map(VfsManagedFile::relativePath).toList();
        assertTrue(added.contains("deep/nested/x.md"), "an overflow should rescan recursively, got: " + added);
        service.dispose();
    }

    /**
     * A change inside an excluded subtree must not register its children. The name filter doesn't match
     * {@code .git/config} itself, and a non-recursive refresh starting inside the subtree never hits SKIP_SUBTREE.
     */
    @Test
    void changeInsideExcludedSubtreeDoesNotRegisterItsChildren(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve(".git"));
        ScanFilter excludesGit = (p, d) -> {
            Path name = p.getFileName();
            return name == null || !name.toString().equals(".git");
        };
        DatabaseHandle db = openVfsDb("scope.excl");
        CapturingFactory factory = new CapturingFactory();
        DefaultManagedVfsService service = new DefaultManagedVfsService(factory, (scope, diff) -> { });
        VfsCatalog catalog = service.openCatalog("scope.excl", DatabaseVfsCatalogBackend.of(db));
        VfsRootId rootId = catalog.registerRoot("proj", "proj", root);
        catalog.scanRoot(rootId, excludesGit);
        assertFalse(catalog.findFile(rootId, ".git").isPresent(), "precondition: the full scan excluded .git");

        service.watch("scope.excl", rootId, root, excludesGit);
        Files.writeString(root.resolve(".git").resolve("config"), "x");
        factory.listener.changed(Set.of(root.resolve(".git")));   // The changed file's parent.

        assertFalse(catalog.findFile(rootId, ".git/config").isPresent(),
                "a file under an excluded directory must not slip into the snapshot through the watcher");
        service.dispose();
    }

    /**
     * A newly created directory must still be found; don't drop events for paths missing from the snapshot. The
     * watcher reports a directory event as the directory itself, so the test does too.
     */
    @Test
    void newlyCreatedDirectoryIsStillDiscovered(@TempDir Path root) throws Exception {
        Path docs = Files.createDirectories(root.resolve("docs"));
        DatabaseHandle db = openVfsDb("scope.new");
        CapturingFactory factory = new CapturingFactory();
        DefaultManagedVfsService service = new DefaultManagedVfsService(factory, (scope, diff) -> { });
        VfsCatalog catalog = service.openCatalog("scope.new", DatabaseVfsCatalogBackend.of(db));
        VfsRootId rootId = catalog.registerRoot("proj", "proj", root);
        catalog.scanRoot(rootId, (p, d) -> true);
        assertTrue(catalog.findFile(rootId, "docs").isPresent(), "precondition: docs is registered");

        service.watch("scope.new", rootId, root, (p, d) -> true);
        Path created = Files.createDirectories(docs.resolve("新文件夹"));
        Files.writeString(created.resolve("a.md"), "hi");
        factory.listener.changed(Set.of(created));

        assertTrue(catalog.findFile(rootId, "docs/新文件夹").isPresent(),
                "a new directory must be found by reconciling its nearest registered parent, not dropped");
        assertTrue(catalog.findFile(rootId, "docs/新文件夹/a.md").isPresent(),
                "the files inside must be registered too, not just the directory row");
        service.dispose();
    }

    /**
     * A tree copied in from outside is registered down to its deepest file. The trailing debounce puts the events in
     * <b>one batch</b>, so the refresh must go level by level, not just at each resolved target.
     */
    @Test
    void wholeCopiedInTreeIsRegisteredDownToTheDeepestFile(@TempDir Path root) throws Exception {
        Path docs = Files.createDirectories(root.resolve("docs"));
        DatabaseHandle db = openVfsDb("scope.tree");
        CapturingFactory factory = new CapturingFactory();
        DefaultManagedVfsService service = new DefaultManagedVfsService(factory, (scope, diff) -> { });
        VfsCatalog catalog = service.openCatalog("scope.tree", DatabaseVfsCatalogBackend.of(db));
        VfsRootId rootId = catalog.registerRoot("proj", "proj", root);
        catalog.scanRoot(rootId, (p, d) -> true);

        service.watch("scope.tree", rootId, root, (p, d) -> true);
        Path sub = Files.createDirectories(docs.resolve("folder").resolve("sub"));
        Files.writeString(sub.resolve("x.md"), "deep");
        // Two directories and a file: a directory reports itself and a file its parent, merged by the debounce.
        factory.listener.changed(Set.of(docs.resolve("folder"), sub));

        assertTrue(catalog.findFile(rootId, "docs/folder").isPresent(), "the first level should be registered");
        assertTrue(catalog.findFile(rootId, "docs/folder/sub").isPresent(), "the second level should be registered");
        assertTrue(catalog.findFile(rootId, "docs/folder/sub/x.md").isPresent(),
                "the deepest file must be registered, or the copied folder looks empty");
        service.dispose();
    }

    /**
     * Events inside an excluded subtree are dropped <b>before the queue</b>, not just kept out of the snapshot;
     * otherwise each write to a database there would reset the debounce and keep delaying real changes.
     */
    @Test
    void eventsInsideExcludedSubtreeAreDroppedBeforeTheDebounceQueue(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve(".myapp"));
        Files.createDirectories(root.resolve("docs"));
        ScanFilter excludesMeta = (p, d) -> {
            Path name = p.getFileName();
            return name == null || !name.toString().equals(".myapp");
        };
        DatabaseHandle db = openVfsDb("scope.queue");
        CapturingFactory factory = new CapturingFactory();
        DefaultManagedVfsService service = new DefaultManagedVfsService(factory, (scope, diff) -> { });
        VfsCatalog catalog = service.openCatalog("scope.queue", DatabaseVfsCatalogBackend.of(db));
        VfsRootId rootId = catalog.registerRoot("proj", "proj", root);
        service.watch("scope.queue", rootId, root, excludesMeta);

        assertFalse(factory.filter.include(root.resolve(".myapp").resolve("project.sqlite"), false),
                "events under an excluded directory must be dropped before the queue by the ancestor check");
        assertFalse(factory.filter.include(root.resolve(".myapp"), true),
                "events for the excluded directory itself must be dropped too");
        assertTrue(factory.filter.include(root.resolve("docs").resolve("a.md"), false),
                "events for ordinary files must not be dropped");
        service.dispose();
    }

    /**
     * A snapshot that already holds a {@code .git} row from before it was excluded still keeps new files under it
     * out. Unlike {@code changeInsideExcludedSubtreeDoesNotRegisterItsChildren}, the excluded row exists here, so
     * this guards the "drop when an ancestor is excluded" check.
     */
    @Test
    void staleSnapshotRowForExcludedDirDoesNotLetItsChildrenBack(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve(".git"));
        DatabaseHandle db = openVfsDb("scope.stale");
        CapturingFactory factory = new CapturingFactory();
        DefaultManagedVfsService service = new DefaultManagedVfsService(factory, (scope, diff) -> { });
        VfsCatalog catalog = service.openCatalog("scope.stale", DatabaseVfsCatalogBackend.of(db));
        VfsRootId rootId = catalog.registerRoot("proj", "proj", root);
        // An older snapshot scanned with no filter, so the .git row exists.
        catalog.scanRoot(rootId, ScanFilter.all());
        assertTrue(catalog.findFile(rootId, ".git").isPresent(), "precondition: the existing snapshot has a .git row");

        // Now the filter excludes .git.
        ScanFilter excludesGit = (p, d) -> {
            Path name = p.getFileName();
            return name == null || !name.toString().equals(".git");
        };
        service.watch("scope.stale", rootId, root, excludesGit);
        Files.writeString(root.resolve(".git").resolve("config"), "x");
        factory.listener.changed(Set.of(root.resolve(".git")));

        assertFalse(catalog.findFile(rootId, ".git/config").isPresent(),
                "an excluded ancestor drops the event even if the snapshot has a .git row to reconcile");
        service.dispose();
    }

    /**
     * As above, but the event reports a <b>deeper</b> directory, {@code .git/objects}, whose own name isn't
     * excluded, so only the <b>ancestor</b> check in {@code refreshTarget} stops it. Calls {@code listener.changed}
     * directly to bypass {@code acceptPath}; don't drop the {@code refreshTarget} checks as redundant.
     */
    @Test
    void excludedDeepDirIsDroppedEvenWhenTheEventBypassesAcceptPath(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve(".git").resolve("objects"));
        DatabaseHandle db = openVfsDb("scope.deep");
        CapturingFactory factory = new CapturingFactory();
        DefaultManagedVfsService service = new DefaultManagedVfsService(factory, (scope, diff) -> { });
        VfsCatalog catalog = service.openCatalog("scope.deep", DatabaseVfsCatalogBackend.of(db));
        VfsRootId rootId = catalog.registerRoot("proj", "proj", root);
        // An older snapshot scanned with no filter, so both the .git and .git/objects rows exist.
        catalog.scanRoot(rootId, ScanFilter.all());
        assertTrue(catalog.findFile(rootId, ".git").isPresent(), "precondition: the existing snapshot has a .git row");
        assertTrue(catalog.findFile(rootId, ".git/objects").isPresent(),
                "precondition: it has a .git/objects row too, which the ancestor check must stop");

        ScanFilter excludesGit = (p, d) -> {
            Path name = p.getFileName();
            return name == null || !name.toString().equals(".git");
        };
        service.watch("scope.deep", rootId, root, excludesGit);
        Files.writeString(root.resolve(".git").resolve("objects").resolve("abc123"), "x");
        factory.listener.changed(Set.of(root.resolve(".git").resolve("objects")));

        assertFalse(catalog.findFile(rootId, ".git/objects/abc123").isPresent(),
                "an excluded ancestor must drop the event; the path's own name isn't excluded");
        service.dispose();
    }

    /**
     * A directory deleted outside and recreated with a file revives its MISSING row and publishes it as added.
     * Don't target the refresh at the MISSING row itself: a non-recursive refresh only walks children.
     */
    @Test
    void aStaleMissingDirRowDoesNotStopItselfFromComingBack(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("docs"));
        DatabaseHandle db = openVfsDb("scope.revive");
        CapturingFactory factory = new CapturingFactory();
        AtomicReference<VfsScanResult> published = new AtomicReference<>();
        DefaultManagedVfsService service = new DefaultManagedVfsService(factory,
                (scope, diff) -> published.set(diff));
        VfsCatalog catalog = service.openCatalog("scope.revive", DatabaseVfsCatalogBackend.of(db));
        VfsRootId rootId = catalog.registerRoot("proj", "proj", root);
        catalog.scanRoot(rootId, ScanFilter.all());

        // docs is deleted outside, and reconcile marks its row MISSING.
        Files.delete(root.resolve("docs"));
        catalog.refresh(rootId, "", true, ScanFilter.all());
        assertEquals(VfsFileStatus.MISSING, catalog.findFile(rootId, "docs").orElseThrow().status(),
                "precondition: the row is already MISSING");

        // A directory with the same name and a file in it appear outside the app.
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs").resolve("a.md"), "x");
        service.watch("scope.revive", rootId, root, ScanFilter.all());
        factory.listener.changed(Set.of(root.resolve("docs")));

        assertEquals(VfsFileStatus.PRESENT, catalog.findFile(rootId, "docs").orElseThrow().status(),
                "the directory's own row must revive, or the folder isn't shown");
        assertTrue(catalog.findFile(rootId, "docs/a.md").isPresent(), "children are registered as usual");

        VfsScanResult diff = published.get();
        assertNotNull(diff, "this refresh must publish");
        List<String> added = diff.added().stream().map(VfsManagedFile::relativePath).toList();
        assertTrue(added.contains("docs"), "the revived directory row must be in diff.added, got: " + added);
        service.dispose();
    }

    /**
     * A folder tree moved out of the root, which includes deleting it to the recycle bin, yields one DELETE for the
     * folder and none for its parent; the folder and every level below must go MISSING and into the diff.
     */
    @Test
    void folderMovedOutOfTheProjectTakesItsWholeSubtreeWithIt(@TempDir Path base) throws Exception {
        Path root = Files.createDirectories(base.resolve("proj"));
        Path old = root.resolve("docs").resolve("old");
        Files.createDirectories(old.resolve("sub"));
        Files.writeString(old.resolve("a.md"), "a");
        Files.writeString(old.resolve("sub").resolve("b.md"), "b");
        DatabaseHandle db = openVfsDb("scope.moveout");
        CapturingFactory factory = new CapturingFactory();
        AtomicReference<VfsScanResult> published = new AtomicReference<>();
        DefaultManagedVfsService service = new DefaultManagedVfsService(factory,
                (scope, diff) -> published.set(diff));
        VfsCatalog catalog = service.openCatalog("scope.moveout", DatabaseVfsCatalogBackend.of(db));
        VfsRootId rootId = catalog.registerRoot("proj", "proj", root);
        catalog.scanRoot(rootId, ScanFilter.all());
        service.watch("scope.moveout", rootId, root, ScanFilter.all());

        Files.move(old, Files.createDirectories(base.resolve("outside")).resolve("old"));
        factory.listener.changed(Set.of(old));

        for (String path : List.of("docs/old", "docs/old/a.md", "docs/old/sub", "docs/old/sub/b.md")) {
            assertEquals(VfsFileStatus.MISSING, catalog.findFile(rootId, path).orElseThrow().status(),
                    path + " has left the root and must not stay visible");
        }
        assertEquals(VfsFileStatus.PRESENT, catalog.findFile(rootId, "docs").orElseThrow().status());
        List<String> missing = published.get().missing().stream().map(VfsManagedFile::relativePath).toList();
        assertTrue(missing.containsAll(List.of("docs/old", "docs/old/sub/b.md")),
                "the whole vanished subtree must be in diff.missing, got: " + missing);
        service.dispose();
    }

    /**
     * A directory failing to reconcile must not take the rest of its batch down; the failure is a filter that throws
     * for a directory placed first in the batch.
     */
    @Test
    void oneFailingDirectoryDoesNotSwallowTheRestOfTheBatch(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("boom"));
        Files.createDirectories(root.resolve("docs"));
        ScanFilter boomThrows = (path, directory) -> {
            Path name = path.getFileName();
            if (name != null && name.toString().equals("boom")) {
                throw new IllegalStateException("boom");
            }
            return true;
        };
        DatabaseHandle db = openVfsDb("scope.isolate");
        CapturingFactory factory = new CapturingFactory();
        AtomicReference<VfsScanResult> published = new AtomicReference<>();
        DefaultManagedVfsService service = new DefaultManagedVfsService(factory,
                (scope, diff) -> published.set(diff));
        VfsCatalog catalog = service.openCatalog("scope.isolate", DatabaseVfsCatalogBackend.of(db));
        VfsRootId rootId = catalog.registerRoot("proj", "proj", root);
        catalog.scanRoot(rootId, ScanFilter.all());
        service.watch("scope.isolate", rootId, root, boomThrows);

        Files.writeString(root.resolve("docs").resolve("a.md"), "hi");
        factory.listener.changed(new LinkedHashSet<>(List.of(root.resolve("boom"), root.resolve("docs"))));

        assertTrue(catalog.findFile(rootId, "docs/a.md").isPresent(), "later directories still reconcile");
        VfsScanResult diff = published.get();
        assertNotNull(diff, "this pass must still publish");
        assertTrue(diff.added().stream().map(VfsManagedFile::relativePath).toList().contains("docs/a.md"),
                "changes already reconciled must still be published");
        service.dispose();
    }

    /** A scope whose database is closing is skipped; later scopes in the batch still reconcile and publish. */
    @Test
    void aProjectCancelledMidBatchDoesNotStopTheOthers(@TempDir Path base) throws Exception {
        TwoProjects two = twoProjectsWhereAIsClosing(base);
        two.service.watch("scope.a", two.rootIdA, two.rootA, two.cancelsInA);
        two.service.watch("scope.b", two.rootIdB, two.rootB, ScanFilter.all());

        two.factory.listener.changed(new LinkedHashSet<>(List.of(two.rootA.resolve("docs"), two.rootB.resolve("docs"))));

        assertTrue(two.catalogB.findFile(two.rootIdB, "docs/b.md").isPresent(), "B is still reconciled");
        assertTrue(two.published.containsKey("scope.b"), "B's changes are still published");
        two.service.dispose();
    }

    /** During an overflow rescan, a scope whose database is closing is skipped and later scopes are still rescanned. */
    @Test
    void anOverflowRescanCancelledForOneProjectStillRescansTheOthers(@TempDir Path base) throws Exception {
        TwoProjects two = twoProjectsWhereAIsClosing(base);
        two.service.watch("scope.a", two.rootIdA, two.rootA, two.cancelsInA);
        two.service.watch("scope.b", two.rootIdB, two.rootB, ScanFilter.all());

        two.factory.listener.overflow(new LinkedHashSet<>(List.of(
                two.rootA.toAbsolutePath().normalize(), two.rootB.toAbsolutePath().normalize())));

        assertTrue(two.catalogB.findFile(two.rootIdB, "docs/b.md").isPresent(), "B is still rescanned");
        two.service.dispose();
    }

    /** When the debounce thread itself is interrupted, the batch stops and later scopes aren't reconciled. */
    @Test
    void aBatchStopsWhenItsOwnThreadIsInterrupted(@TempDir Path base) throws Exception {
        TwoProjects two = twoProjectsWhereAIsClosing(base);
        two.service.watch("scope.a", two.rootIdA, two.rootA, ScanFilter.all());
        two.service.watch("scope.b", two.rootIdB, two.rootB, ScanFilter.all());
        try {
            Thread.currentThread().interrupt();
            two.factory.listener.changed(new LinkedHashSet<>(List.of(two.rootA.resolve("docs"), two.rootB.resolve("docs"))));
        } finally {
            Thread.interrupted();
        }

        assertFalse(two.catalogB.findFile(two.rootIdB, "docs/b.md").isPresent(), "later scopes aren't reconciled");
        assertFalse(two.published.containsKey("scope.b"));
        two.service.dispose();
    }

    private record TwoProjects(CapturingFactory factory, DefaultManagedVfsService service,
                               Map<String, VfsScanResult> published, Path rootA, Path rootB,
                               VfsCatalog catalogA, VfsCatalog catalogB, VfsRootId rootIdA, VfsRootId rootIdB,
                               ScanFilter cancelsInA) {
    }

    /**
     * Roots A and B each get a new file under docs/; cancelsInA throws a cancellation for entries in A's docs/, as
     * if A's database were closing.
     */
    private TwoProjects twoProjectsWhereAIsClosing(Path base) throws Exception {
        Path rootA = Files.createDirectories(base.resolve("projA"));
        Path rootB = Files.createDirectories(base.resolve("projB"));
        Files.createDirectories(rootA.resolve("docs"));
        Files.createDirectories(rootB.resolve("docs"));
        CapturingFactory factory = new CapturingFactory();
        Map<String, VfsScanResult> published = new ConcurrentHashMap<>();
        DefaultManagedVfsService service = new DefaultManagedVfsService(factory, published::put);
        VfsCatalog catalogA = service.openCatalog("scope.a", DatabaseVfsCatalogBackend.of(openVfsDb("scope.a")));
        VfsCatalog catalogB = service.openCatalog("scope.b", DatabaseVfsCatalogBackend.of(openVfsDb("scope.b")));
        VfsRootId rootIdA = catalogA.registerRoot("a", "a", rootA);
        VfsRootId rootIdB = catalogB.registerRoot("b", "b", rootB);
        catalogA.scanRoot(rootIdA, ScanFilter.all());
        catalogB.scanRoot(rootIdB, ScanFilter.all());
        Files.writeString(rootA.resolve("docs").resolve("a.md"), "a");
        Files.writeString(rootB.resolve("docs").resolve("b.md"), "b");
        Path docsA = rootA.resolve("docs");
        ScanFilter cancelsInA = (path, directory) -> {
            if (path.startsWith(docsA) && !path.equals(docsA)) {
                throw new java.util.concurrent.CancellationException("project A is closing");
            }
            return true;
        };
        return new TwoProjects(factory, service, published, rootA, rootB, catalogA, catalogB, rootIdA, rootIdB, cancelsInA);
    }

    /**
     * While a disk sync is still publishing, a newer watcher reconcile of the same file must publish after it;
     * otherwise a stale "missing" arrives last and subscribers treat a present file as deleted.
     */
    @Test
    void aFullSyncStillPublishingKeepsANewerChangeBehindIt(@TempDir Path root) throws Exception {
        verifySyncPublishesFirst(root, "scope.order", listener -> listener.changed(Set.of(root)));
    }

    @Test
    void aFullSyncStillPublishingKeepsAnOverflowRescanBehindIt(@TempDir Path root) throws Exception {
        verifySyncPublishesFirst(root, "scope.overflow",
                listener -> listener.overflow(Set.of(root.toAbsolutePath().normalize())));
    }

    private void verifySyncPublishesFirst(Path root, String scope, Consumer<VfsWatchListener> incremental)
            throws Exception {
        Path note = root.resolve("a.md");
        Files.writeString(note, "v1");
        DatabaseHandle db = openVfsDb(scope);
        CapturingFactory factory = new CapturingFactory();
        List<VfsScanResult> published = new CopyOnWriteArrayList<>();
        CountDownLatch syncPublishing = new CountDownLatch(1);
        CountDownLatch releaseSync = new CountDownLatch(1);
        DefaultManagedVfsService service = new DefaultManagedVfsService(factory, (changedScope, diff) -> {
            if (syncPublishing.getCount() > 0 && paths(diff.missing()).contains("a.md")) {
                syncPublishing.countDown();
                awaitQuietly(releaseSync);
            }
            published.add(diff);
        });
        VfsCatalog catalog = service.openCatalog(scope, DatabaseVfsCatalogBackend.of(db));
        VfsRootId rootId = catalog.registerRoot("proj", "proj", root);
        catalog.scanRoot(rootId, ScanFilter.all());
        service.watch(scope, rootId, root, ScanFilter.all());

        Files.delete(note);
        Thread sync = new Thread(() -> service.syncFromDisk(scope));
        sync.start();
        assertTrue(syncPublishing.await(10, TimeUnit.SECONDS), "precondition: the sync is publishing a.md as missing");
        Files.writeString(note, "v2");
        Thread change = new Thread(() -> incremental.accept(factory.listener));
        change.start();
        awaitDoneOrParked(change);
        releaseSync.countDown();
        sync.join(10_000);
        change.join(10_000);

        assertEquals(VfsFileStatus.PRESENT, catalog.findFile(rootId, "a.md").orElseThrow().status(), "the file is back");
        List<String> timeline = new ArrayList<>();
        for (VfsScanResult diff : published) {
            if (paths(diff.missing()).contains("a.md")) {
                timeline.add("missing");
            }
            if (paths(diff.added()).contains("a.md") || paths(diff.updated()).contains("a.md")) {
                timeline.add("present");
            }
        }
        assertEquals("present", timeline.get(timeline.size() - 1),
                "the last batch about a.md must match the snapshot, order: " + timeline);
        service.dispose();
    }

    private static List<String> paths(List<VfsManagedFile> files) {
        return files.stream().map(VfsManagedFile::relativePath).toList();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Waits until the thread ends or has been blocked on a lock for 200 ms in a row. */
    private static void awaitDoneOrParked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int blockedPolls = 0;
        while (thread.isAlive() && blockedPolls < 20 && System.nanoTime() < deadline) {
            blockedPolls = thread.getState() == Thread.State.BLOCKED ? blockedPolls + 1 : 0;
            Thread.sleep(10);
        }
    }

    /**
     * A registered directory replaced in place by a junction reports an event for itself; the refresh must not stop
     * at the link but mark it MISSING from its parent, or an empty folder lingers.
     */
    @Test
    void directoryReplacedByAJunctionGoesMissingThroughItsParent(@TempDir Path base) throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"), "junctions need NTFS");
        Path root = base.resolve("root");
        Path lib = root.resolve("lib");
        Files.createDirectories(lib);
        Path outside = base.resolve("outside");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("c.md"), "c");
        DatabaseHandle db = openVfsDb("scope.link");
        CapturingFactory factory = new CapturingFactory();
        DefaultManagedVfsService service = new DefaultManagedVfsService(factory, (scope, diff) -> { });
        VfsCatalog catalog = service.openCatalog("scope.link", DatabaseVfsCatalogBackend.of(db));
        VfsRootId rootId = catalog.registerRoot("proj", "proj", root);
        catalog.scanRoot(rootId, (p, d) -> true);
        service.watch("scope.link", rootId, root, (p, d) -> true);
        Files.delete(lib);
        Process mklink = new ProcessBuilder("cmd", "/c", "mklink", "/J", lib.toString(), outside.toString())
                .redirectErrorStream(true).start();
        try {
            assumeTrue(mklink.waitFor() == 0 && Files.isDirectory(lib), "mklink /J failed");

            factory.listener.changed(Set.of(lib));

            assertEquals(VfsFileStatus.MISSING, catalog.findFile(rootId, "lib").orElseThrow().status());
            assertTrue(catalog.findFile(rootId, "lib/c.md").isEmpty(), "nothing inside the junction may be registered");
        } finally {
            Files.deleteIfExists(lib); // Delete the junction first so @TempDir cleanup doesn't follow it.
            service.dispose();
        }
    }

    /**
     * Opens an empty database; {@code SqliteVfsCatalog} creates the VFS tables. Must include the scope in the name:
     * the file path depends only on owner and name, so scopes would otherwise share one database.
     */
    private DatabaseHandle openVfsDb(String scope) {
        PathManager.setHomePathForTests(homeDir);
        return dbService.open(DatabaseDescriptor.builder(scope)
                .owner(DatabaseOwner.application("tests"))
                .name("vfs-" + scope)
                .build());
    }
}
