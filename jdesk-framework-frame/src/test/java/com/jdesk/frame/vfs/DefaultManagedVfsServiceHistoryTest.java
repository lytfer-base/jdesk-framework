package com.jdesk.frame.vfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jdesk.core.disposer.Disposer;
import com.jdesk.core.history.CaptureRequest;
import com.jdesk.core.history.CaptureResult;
import com.jdesk.core.history.DeletedEntry;
import com.jdesk.core.history.HistoryKey;
import com.jdesk.core.history.HistoryPolicy;
import com.jdesk.core.history.HistorySource;
import com.jdesk.core.history.HistoryStats;
import com.jdesk.core.history.HistoryVersion;
import com.jdesk.core.history.LocalHistoryStore;
import com.jdesk.core.app.PathManager;
import com.jdesk.core.vfs.ScanFilter;
import com.jdesk.core.vfs.VfsCatalog;
import com.jdesk.core.vfs.VfsFileId;
import com.jdesk.core.vfs.VfsHistoryKeys;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Every VFS change path feeds files into local history; scopes without history are unaffected. */
class DefaultManagedVfsServiceHistoryTest {

    private static final ScanFilter ALL = (p, d) -> true;

    @TempDir
    Path homeDir;

    private final SqliteDatabaseService dbService = new SqliteDatabaseService();

    @AfterEach
    void tearDown() {
        dbService.dispose();
        Disposer.clearAll();
        PathManager.resetForTests();
    }

    /** Fake store that keeps every call; tests set up hasHead in advance. */
    static final class RecordingStore implements LocalHistoryStore {
        final List<CaptureRequest> captures = new ArrayList<>();
        final List<List<CaptureRequest>> captureBatches = new ArrayList<>();
        final List<CaptureRequest> baselines = new ArrayList<>();
        final List<HistoryKey> deleted = new ArrayList<>();
        final List<List<HistoryKey>> deletedBatches = new ArrayList<>();
        final Map<HistoryKey, String> deletedPaths = new LinkedHashMap<>();
        final Set<String> heads = new HashSet<>();

        @Override
        public String scopeId() {
            return "scope";
        }

        @Override
        public HistoryPolicy policy() {
            return HistoryPolicy.defaults();
        }

        @Override
        public CompletableFuture<CaptureResult> capture(CaptureRequest request) {
            captures.add(request);
            heads.add(request.key().id());
            return CompletableFuture.completedFuture(CaptureResult.captured("h", 1));
        }

        @Override
        public void captureAll(Collection<CaptureRequest> requests) {
            captureBatches.add(List.copyOf(requests));
            requests.forEach(this::capture);
        }

        @Override
        public CompletableFuture<CaptureResult> captureBaseline(CaptureRequest request) {
            baselines.add(request);
            heads.add(request.key().id());
            return CompletableFuture.completedFuture(CaptureResult.captured("h", 1));
        }

        @Override
        public void seal(HistoryKey key) {
        }

        @Override
        public void sealAll() {
        }

        @Override
        public void markDeleted(HistoryKey key, long deletedAt) {
            // The VFS reconcile thread is shared app-wide, so feeding history must not wait.
            throw new AssertionError("VFS must not use the waiting markDeleted: " + key);
        }

        @Override
        public void markDeletedLater(HistoryKey key, long deletedAt) {
            // Marking one key at a time would multiply the write cost by the files in a deleted subtree.
            throw new AssertionError("VFS must hand delete marks over in batches: " + key);
        }

        @Override
        public void markDeletedLater(Collection<HistoryKey> keys, long deletedAt) {
            deletedBatches.add(List.copyOf(keys));
            deleted.addAll(keys);
        }

        @Override
        public void markDeletedLater(Map<HistoryKey, String> pathsByKey, long deletedAt) {
            deletedPaths.putAll(pathsByKey);
            markDeletedLater(pathsByKey.keySet(), deletedAt);
        }

        @Override
        public void unmarkDeleted(HistoryKey key) {
        }

        @Override
        public boolean hasHead(HistoryKey key) {
            return heads.contains(key.id());
        }

        @Override
        public long beginActivity(String name, HistorySource source) {
            return 1;
        }

        @Override
        public void endActivity(long activityId) {
        }

        @Override
        public List<HistoryVersion> versions(HistoryKey key) {
            return List.of();
        }

        @Override
        public Optional<HistoryVersion> version(long versionId) {
            return Optional.empty();
        }

        @Override
        public Optional<byte[]> content(long versionId) {
            return Optional.empty();
        }

        @Override
        public List<DeletedEntry> deletedEntries() {
            return List.of();
        }

        @Override
        public void label(long versionId, String label) {
        }

        @Override
        public void purge(HistoryKey key) {
        }

        @Override
        public void purgeExpiredDeleted() {
        }

        @Override
        public void rekey(HistoryKey from, HistoryKey to) {
        }

        @Override
        public Set<String> objectHashes(String kind) {
            return Set.of();
        }

        @Override
        public Set<String> keysWithHead(String kind) {
            return Set.copyOf(heads);
        }

        @Override
        public HistoryStats stats() {
            return new HistoryStats(0, 0, null);
        }

        @Override
        public void maintain() {
        }

        List<String> capturedPaths() {
            return captures.stream().map(CaptureRequest::path).toList();
        }

        List<String> baselinePaths() {
            return baselines.stream().map(CaptureRequest::path).toList();
        }
    }

    private static final class CapturingFactory implements VfsWatcherFactory {
        VfsWatchListener listener;

        @Override
        public VfsWatcher create(ScanFilter filter, VfsWatchListener listener) {
            this.listener = listener;
            return new VfsWatcher() {
                @Override
                public void setRoots(Set<Path> newRoots) {
                }

                @Override
                public void dispose() {
                }
            };
        }
    }

    private record Fixture(DefaultManagedVfsService service, VfsCatalog catalog, VfsRootId rootId, RecordingStore store,
                           CapturingFactory factory) {
    }

    private Fixture open(Path root, String scope) {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle db = dbService.open(DatabaseDescriptor.builder(scope)
                .owner(DatabaseOwner.application("tests")).name(scope).build());
        RecordingStore store = new RecordingStore();
        CapturingFactory factory = new CapturingFactory();
        DefaultManagedVfsService service = new DefaultManagedVfsService(factory, (s, diff) -> { },
                id -> id.equals(scope) ? Optional.of(store) : Optional.empty());
        VfsCatalog catalog = service.openCatalog(scope, DatabaseVfsCatalogBackend.of(db));
        VfsRootId rootId = catalog.registerRoot("proj", "proj", root);
        return new Fixture(service, catalog, rootId, store, factory);
    }

    @Test
    void reconcileBaselinesSmallTextOnlyAndCapturesChangedFilesWithHistory(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("a.md"), "small");
        Files.write(root.resolve("big.md"), new byte[70 * 1024]);
        Files.write(root.resolve("img.png"), new byte[] {1, 2, 3});
        Fixture fx = open(root, "scope.reconcile");

        VfsScanResult first = fx.service().reconcile("scope.reconcile", fx.rootId(), ALL);
        assertEquals(3, first.added().size());
        assertEquals(List.of("a.md"), fx.store().baselinePaths(), "only small text files get a baseline");
        assertTrue(fx.store().capturedPaths().isEmpty(), "a file seen for the first time isn't a change");

        Thread.sleep(20);
        Files.writeString(root.resolve("a.md"), "small but changed");
        Files.write(root.resolve("img.png"), new byte[] {9, 9, 9});
        Files.setLastModifiedTime(root.resolve("a.md"), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000));
        Files.setLastModifiedTime(root.resolve("img.png"), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000));
        fx.service().reconcile("scope.reconcile", fx.rootId(), ALL);
        assertEquals(List.of("a.md"), fx.store().capturedPaths(), "only changed files with history are captured");
        assertEquals(HistorySource.EXTERNAL, fx.store().captures.get(0).source());
        fx.service().dispose();
    }

    @Test
    void watcherBatchCapturesExternalAndMissingMarksDeleted(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs").resolve("a.md"), "one");
        Files.writeString(root.resolve("docs").resolve("b.md"), "two");
        Fixture fx = open(root, "scope.watch");
        fx.service().reconcile("scope.watch", fx.rootId(), ALL);
        fx.store().captures.clear();
        fx.service().watch("scope.watch", fx.rootId(), root, ALL);

        Files.writeString(root.resolve("docs").resolve("a.md"), "one changed");
        Files.setLastModifiedTime(root.resolve("docs").resolve("a.md"),
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000));
        Files.delete(root.resolve("docs").resolve("b.md"));
        fx.factory().listener.changed(Set.of(root.resolve("docs")));

        assertEquals(List.of("docs/a.md"), fx.store().capturedPaths());
        assertEquals(HistorySource.EXTERNAL, fx.store().captures.get(0).source());
        VfsFileId bId = fx.catalog().findFile(fx.rootId(), "docs/b.md").orElseThrow().fileId();
        assertEquals(List.of(VfsHistoryKeys.of(bId)), fx.store().deleted);
        assertEquals(Map.of(VfsHistoryKeys.of(bId), "docs/b.md"), fx.store().deletedPaths, "with the path at deletion");
        assertEquals(1, fx.store().deletedBatches.size(), "the batch's delete marks go in one call");
        assertEquals(1, fx.store().captureBatches.size(), "the batch's captures go in one call, not one capture each");
        fx.service().dispose();
    }

    /** An external rename lists one file_id in both added and missing; the file lives on, so it gets no delete mark. */
    @Test
    void externalRenameIsNotMarkedDeleted(@TempDir Path root) throws Exception {
        Path docs = Files.createDirectories(root.resolve("docs"));
        Files.writeString(docs.resolve("a.md"), "one");
        Files.writeString(docs.resolve("gone.md"), "two");
        Fixture fx = open(root, "scope.rename");
        fx.service().reconcile("scope.rename", fx.rootId(), ALL);
        fx.service().watch("scope.rename", fx.rootId(), root, ALL);
        VfsFileId renamed = fx.catalog().findFile(fx.rootId(), "docs/a.md").orElseThrow().fileId();
        VfsFileId gone = fx.catalog().findFile(fx.rootId(), "docs/gone.md").orElseThrow().fileId();

        Files.move(docs.resolve("a.md"), docs.resolve("b.md"));
        Files.delete(docs.resolve("gone.md"));
        fx.factory().listener.changed(Set.of(docs));

        assertEquals(renamed, fx.catalog().findFile(fx.rootId(), "docs/b.md").orElseThrow().fileId(), "seen as a rename");
        assertEquals(List.of(VfsHistoryKeys.of(gone)), fx.store().deleted, "only the really deleted file is marked");
        fx.service().dispose();
    }

    /** History writes are queued before the change is published, so a subscriber reloading at once sees them. */
    @Test
    void historyIsQueuedBeforeTheChangeIsPublished(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs").resolve("a.md"), "one");
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle db = dbService.open(DatabaseDescriptor.builder("scope.order")
                .owner(DatabaseOwner.application("tests")).name("scope.order").build());
        RecordingStore store = new RecordingStore();
        CapturingFactory factory = new CapturingFactory();
        List<Integer> deletedAtPublish = new ArrayList<>();
        DefaultManagedVfsService service = new DefaultManagedVfsService(factory,
                (s, diff) -> deletedAtPublish.add(store.deleted.size()),
                id -> id.equals("scope.order") ? Optional.of(store) : Optional.empty());
        VfsCatalog catalog = service.openCatalog("scope.order", DatabaseVfsCatalogBackend.of(db));
        VfsRootId rootId = catalog.registerRoot("proj", "proj", root);
        service.reconcile("scope.order", rootId, ALL);
        service.watch("scope.order", rootId, root, ALL);
        deletedAtPublish.clear();

        Files.delete(root.resolve("docs").resolve("a.md"));
        factory.listener.changed(Set.of(root.resolve("docs")));

        assertEquals(List.of(1), deletedAtPublish, "the delete mark reaches history before the publish");
        service.dispose();
    }

    @Test
    void notifyChangedAndFileWrittenCaptureAsApp(@TempDir Path root) throws Exception {
        Fixture fx = open(root, "scope.app");
        fx.service().watch("scope.app", fx.rootId(), root, ALL);
        Files.writeString(root.resolve("n.md"), "written by app");
        VfsManagedFile registered = fx.catalog().registerFile(fx.rootId(), "n.md", false);
        fx.service().notifyChanged("scope.app", List.of(registered.fileId()), List.of());
        assertEquals(List.of("n.md"), fx.store().capturedPaths());
        assertEquals(HistorySource.APP, fx.store().captures.get(0).source());

        Files.writeString(root.resolve("n.md"), "written again");
        Files.setLastModifiedTime(root.resolve("n.md"),
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000));
        VfsManagedFile row = fx.service().fileWritten("scope.app", registered.fileId());
        assertEquals("n.md", row.relativePath());
        assertEquals(registered.contentVersion() + 1, row.contentVersion(), "fileWritten reconciles first");
        assertEquals(2, fx.store().captures.size());
        assertEquals(HistorySource.APP, fx.store().captures.get(1).source());
        fx.service().dispose();
    }

    @Test
    void fileOpenedBaselinesTextWithoutHead(@TempDir Path root) throws Exception {
        Files.write(root.resolve("big.md"), new byte[70 * 1024]);
        Files.write(root.resolve("pic.png"), new byte[] {1});
        Fixture fx = open(root, "scope.open");
        fx.service().reconcile("scope.open", fx.rootId(), ALL);
        assertTrue(fx.store().baselinePaths().isEmpty(), "a large text file gets no baseline on reconcile");
        VfsFileId big = fx.catalog().findFile(fx.rootId(), "big.md").orElseThrow().fileId();
        VfsFileId pic = fx.catalog().findFile(fx.rootId(), "pic.png").orElseThrow().fileId();
        fx.service().fileOpened("scope.open", big);
        fx.service().fileOpened("scope.open", pic);
        fx.service().fileOpened("scope.open", big);
        assertEquals(List.of("big.md"), fx.store().baselinePaths(), "baseline once on open, never for binaries");
        fx.service().dispose();
    }

    @Test
    void scopeWithoutStoreIsUntouched(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("a.md"), "x");
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle db = dbService.open(DatabaseDescriptor.builder("scope.none")
                .owner(DatabaseOwner.application("tests")).name("scope.none").build());
        List<VfsScanResult> published = new ArrayList<>();
        DefaultManagedVfsService service = new DefaultManagedVfsService(new CapturingFactory(),
                (s, diff) -> published.add(diff), id -> Optional.empty());
        VfsCatalog catalog = service.openCatalog("scope.none", DatabaseVfsCatalogBackend.of(db));
        VfsRootId rootId = catalog.registerRoot("proj", "proj", root);
        VfsScanResult result = service.reconcile("scope.none", rootId, ALL);
        assertEquals(1, result.added().size());
        assertTrue(published.isEmpty(), "an unwatched scope reconciles without publishing");
        service.watch("scope.none", rootId, root, ALL);
        service.reconcile("scope.none", rootId, ALL);
        assertEquals(1, published.size(), "once watched, reconcile results are published");
        service.dispose();
    }
}
