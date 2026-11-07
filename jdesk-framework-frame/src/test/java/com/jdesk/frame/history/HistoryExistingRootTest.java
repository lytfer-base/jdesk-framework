package com.jdesk.frame.history;

import com.jdesk.core.disposer.Disposer;
import com.jdesk.core.history.*;
import com.jdesk.core.io.PathAccessPolicy;
import com.jdesk.frame.storage.StorageException;
import com.jdesk.frame.storage.DatabaseDescriptor;
import com.jdesk.frame.storage.DatabaseOwner;
import com.jdesk.frame.storage.sqlite.SqliteDatabaseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises existing-root history against actual SQLite and object files. */
class HistoryExistingRootTest {
    private static final HistoryKey KEY = HistoryKey.of("file", "note");
    @TempDir Path run;
    private final SqliteDatabaseService databases = new SqliteDatabaseService();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ManualScheduler scheduler = new ManualScheduler();
    private final DefaultLocalHistoryService service = new DefaultLocalHistoryService(
            () -> databases, executor, scheduler, Clock.systemUTC());

    @AfterEach
    void close() {
        service.dispose();
        Disposer.dispose(databases);
        Disposer.clearAll();
    }

    @Test
    void strictOpenDoesNotCreateAMovedRootAndCanRetryAfterItReturns() throws Exception {
        Path root = prepare();
        Path moved = run.resolve("moved");
        moveOnceReleased(root, moved);
        try (var scope = PathAccessPolicy.existingRoot(root).enter()) {
            assertThrows(RuntimeException.class, () -> open(root));
            assertTrue(service.findOpenStore("scope").isEmpty());
        }
        assertFalse(Files.exists(root));
        moveOnceReleased(moved, root);
        try (var scope = PathAccessPolicy.existingRoot(root).enter()) {
            assertTrue(open(root).stats().available());
        }
    }

    @Test
    void strictOpenRequiresPreparedHistoryWithoutEvenWritingGitignore() throws Exception {
        Path root = Files.createDirectory(run.resolve("project"));
        Path dir = Files.createDirectory(root.resolve("history"));
        try (var scope = PathAccessPolicy.existingRoot(root).enter()) {
            assertThrows(RuntimeException.class, () -> open(root));
        }
        try (var entries = Files.list(dir)) { assertEquals(0, entries.count()); }
        assertTrue(service.findOpenStore("scope").isEmpty());
        assertTrue(open(root).stats().available(), "explicit default preparation remains available");
    }

    @Test
    void strictOpenDoesNotQuarantineOrReplaceACorruptDatabase() throws Exception {
        Path root = Files.createDirectory(run.resolve("project"));
        Path dir = Files.createDirectory(root.resolve("history"));
        Path file = Files.writeString(dir.resolve("history.sqlite"), "broken original");
        try (var scope = PathAccessPolicy.existingRoot(root).enter()) {
            assertThrows(RuntimeException.class, () -> open(root));
        }
        assertEquals("broken original", Files.readString(file));
        try (var entries = Files.list(dir)) {
            assertTrue(entries.noneMatch(p -> p.getFileName().toString().contains(".broken-")));
        }
        assertTrue(service.findOpenStore("scope").isEmpty());
        assertTrue(open(root).stats().available(), "default corruption recovery is unchanged");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void strictOpenRejectsMissingHistorySchemaEvenWhenMigrationVersionIsAlreadyRecorded(boolean partial) throws Exception {
        Path root = Files.createDirectory(run.resolve("project"));
        Path dir = root.resolve("history");
        if (partial) {
            var prepared = HistoryTestSupport.openDatabase("prepared.history", dir);
            prepared.write(s -> s.update("alter table lh_activities drop column params"));
            prepared.close();
        } else {
            databases.open(DatabaseDescriptor.builder("blank").owner(DatabaseOwner.application("tests"))
                    .name("history").location(dir).build()).close();
        }
        try (var scope = PathAccessPolicy.existingRoot(root).enter()) {
            assertThrows(RuntimeException.class, () -> open(root));
            assertTrue(service.findOpenStore("scope").isEmpty());
        }
        var inspection = databases.open(DatabaseDescriptor.builder("inspect").owner(DatabaseOwner.application("tests"))
                .name("history").location(dir).build());
        try {
            long tableCount = inspection.read(s -> s.queryOne(
                    "select count(*) as n from sqlite_master where type='table' and name like 'lh_%'",
                    row -> row.getLong("n")).orElseThrow());
            assertEquals(partial ? 4 : 0, tableCount, "strict mode must not initialize missing history tables");
            assertTrue(inspection.read(s -> s.queryOne(
                    "select count(*) as n from sqlite_master where name='jdesk_schema_version'",
                    row -> row.getLong("n")).orElseThrow()) > 0, "framework migration metadata is permitted");
        } finally {
            inspection.close();
        }
    }

    @Test
    void incompatibleCachedDirectoryOrHistoryPolicyIsRejectedWithoutClosingTheOwner() throws Exception {
        Path root = prepare();
        LocalHistoryStore owner = open(root);
        assertThrows(IllegalArgumentException.class,
                () -> service.openStore("scope", run.resolve("other"), HistoryPolicy.defaults()));
        HistoryPolicy p = HistoryPolicy.defaults();
        HistoryPolicy changed = new HistoryPolicy(p.foldWindow(), p.externalIdleGap(), p.maxObjectBytes(),
                p.eagerBaselineMaxBytes(), p.libraryCapBytes() + 1, p.deletedRetention(), p.textExtensions());
        assertThrows(IllegalArgumentException.class, () -> service.openStore("scope", root.resolve("history"), changed));
        assertSame(owner, open(root));
        assertTrue(owner.capture(request("still writable")).get(5, TimeUnit.SECONDS).captured());
        assertFalse(Files.exists(run.resolve("other")));
    }

    @Test
    void aPreviousDefaultUnavailableStoreDoesNotPoisonAStrictRetry() throws Exception {
        Path root = Files.createDirectory(run.resolve("project"));
        Path dir = Files.writeString(root.resolve("history"), "blocking file");
        assertFalse(open(root).stats().available());
        Files.delete(dir);
        var prepared = HistoryTestSupport.openDatabase("prepared.history", dir);
        prepared.close();
        try (var scope = PathAccessPolicy.existingRoot(root).enter()) {
            assertTrue(open(root).stats().available());
        }
    }

    @Test
    void orderedFallbackAfterExecutorShutdownStillUsesTheCallingPolicy() throws Exception {
        Path root = prepare();
        LocalHistoryStore owner = open(root);
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        Path file = root.resolve("history/history.sqlite");
        Path displaced = root.resolve("history/displaced.sqlite");
        Files.move(file, displaced);
        try (var scope = PathAccessPolicy.existingRoot(root).enter()) {
            assertThrows(StorageException.class, () -> owner.markDeleted(KEY, 1));
        }
        assertFalse(Files.exists(file));
        assertTrue(owner.awaitPending(java.time.Duration.ZERO));
        Files.move(displaced, file);
        assertTrue(owner.stats().available());
    }

    @Test
    void orderedReentryOnTheCaptureThreadPreservesTheCapturedPolicy() throws Exception {
        Path root = Files.createDirectory(run.resolve("project"));
        var database = HistoryTestSupport.openDatabase("direct.history", root.resolve("history"));
        AtomicReference<LocalHistoryStore> holder = new AtomicReference<>();
        AtomicReference<PathAccessPolicy> observed = new AtomicReference<>();
        AtomicBoolean once = new AtomicBoolean();
        Clock reentering = new Clock() {
            @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(millis()); }
            @Override public long millis() {
                if (once.compareAndSet(false, true)) {
                    observed.set(PathAccessPolicy.current());
                    holder.get().markDeleted(KEY, 1);
                }
                return HistoryTestSupport.BASE;
            }
        };
        var store = new SqliteLocalHistoryStore("direct", database, new HistoryObjectStore(root.resolve("history/objects")),
                HistoryPolicy.defaults(), executor, reentering);
        holder.set(store);
        var policy = PathAccessPolicy.existingRoot(root);
        try {
            CompletableFuture<CaptureResult> result;
            try (var scope = policy.enter()) { result = store.capture(request("nested")); }
            assertTrue(result.get(5, TimeUnit.SECONDS).captured(), "direct ordered reentry must not deadlock");
            assertEquals(policy, observed.get());
            assertFalse(executor.submit(() -> PathAccessPolicy.current().requiresExistingRoot()).get(5, TimeUnit.SECONDS));
        } finally {
            database.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void defaultAndStrictCallsShareAHealthyStoreInEitherOpeningOrder(boolean strictFirst) throws Exception {
        Path root = prepare();
        LocalHistoryStore first;
        if (strictFirst) {
            try (var scope = PathAccessPolicy.existingRoot(root).enter()) { first = open(root); }
        } else {
            first = open(root);
        }
        try (var scope = PathAccessPolicy.existingRoot(root).enter()) {
            assertSame(first, open(root));
            assertTrue(first.captureBaseline(request("strict text")).get(5, TimeUnit.SECONDS).captured());
            byte[] image = new byte[]{0, 1, 2, 3, 4};
            var result = first.capture(CaptureRequest.of(HistoryKey.of("asset", "image"), "image.png",
                    ContentSource.bytes(image), HistorySource.APP)).get(5, TimeUnit.SECONDS);
            assertArrayEquals(image, first.content(result.versionId()).orElseThrow());
        }
        assertSame(first, open(root));
        assertTrue(first.capture(request("ordinary text")).get(5, TimeUnit.SECONDS).captured());
        assertEquals("ordinary text", new String(first.content(first.versions(KEY).get(0).versionId()).orElseThrow(), StandardCharsets.UTF_8));
        assertFalse(PathAccessPolicy.current().requiresExistingRoot());
    }

    @Test
    void strictCachedOpenRejectsMissingFilesWithoutDisposingTheOwner() throws Exception {
        Path root = prepare();
        LocalHistoryStore owner = open(root);
        Path file = root.resolve("history/history.sqlite");
        Path moved = root.resolve("history/displaced.sqlite");
        Files.move(file, moved);
        try (var scope = PathAccessPolicy.existingRoot(root).enter()) {
            assertThrows(RuntimeException.class, () -> open(root));
            assertThrows(RuntimeException.class, () -> service.findOpenStore("scope"));
        }
        assertFalse(Files.exists(file));
        Files.move(moved, file);
        assertSame(owner, open(root));
        assertTrue(owner.stats().available());
    }

    @ParameterizedTest
    @CsvSource({"capture,false", "baseline,false", "batch,false", "ordered,false",
            "capture,true", "baseline,true", "batch,true", "ordered,true"})
    void queuedOperationsRetainStrictPolicyAfterTheCallerLeaves(String operation, boolean strictFirst) throws Exception {
        Path root = prepare();
        LocalHistoryStore owner;
        if (strictFirst) {
            try (var scope = PathAccessPolicy.existingRoot(root).enter()) { owner = open(root); }
        } else {
            owner = open(root);
        }
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.submit(() -> { entered.countDown(); release.await(5, TimeUnit.SECONDS); return null; });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        CompletableFuture<CaptureResult> capture = null;
        try (var scope = PathAccessPolicy.existingRoot(root).enter()) {
            switch (operation) {
                case "capture" -> capture = owner.capture(request("late"));
                case "baseline" -> capture = owner.captureBaseline(request("late"));
                case "batch" -> owner.captureAll(List.of(request("late")));
                case "ordered" -> owner.markDeletedLater(KEY, 1);
                default -> fail(operation);
            }
        }
        assertSame(owner, open(root), "ordinary adoption must not relax already queued strict work");
        Path file = root.resolve("history/history.sqlite");
        Path moved = root.resolve("history/displaced.sqlite");
        try {
            Files.move(file, moved);
        } finally {
            release.countDown();
        }
        owner.afterQueued().get(5, TimeUnit.SECONDS);
        if (capture != null) assertFalse(capture.get(5, TimeUnit.SECONDS).captured());
        assertTrue(owner.awaitPending(java.time.Duration.ofSeconds(1)));
        assertFalse(Files.exists(file), operation + " recreated the database");
        assertFalse(Files.exists(root.resolve("history/objects")));
        assertFalse(executor.submit(() -> PathAccessPolicy.current().requiresExistingRoot()).get(5, TimeUnit.SECONDS));
        Files.move(moved, file);
        assertTrue(owner.capture(request("after restore")).get(5, TimeUnit.SECONDS).captured());
    }

    @ParameterizedTest
    @ValueSource(strings = {"maintenance", "close"})
    void strictFirstBackgroundWorkKeepsItsPolicyAfterOpenReturns(String operation) throws Exception {
        Path root = prepare();
        try (var scope = PathAccessPolicy.existingRoot(root).enter()) { open(root); }
        Path file = root.resolve("history/history.sqlite");
        Files.move(file, root.resolve("history/displaced.sqlite"));
        if (operation.equals("close")) service.closeStore("scope");
        else scheduler.runLatest();
        assertFalse(Files.exists(file), operation + " recreated the database");
        assertFalse(PathAccessPolicy.current().requiresExistingRoot());
    }

    @Test
    void anOrdinaryOpenAdoptsFutureBackgroundWorkWithoutChangingQueuedStrictWork() throws Exception {
        Path root = prepare();
        try (var scope = PathAccessPolicy.existingRoot(root).enter()) { open(root); }
        LocalHistoryStore owner = open(root);
        assertTrue(owner.capture(request("ordinary")).get(5, TimeUnit.SECONDS).captured());
        Path file = root.resolve("history/history.sqlite");
        Files.move(file, root.resolve("history/displaced.sqlite"));
        scheduler.runLatest();
        assertTrue(Files.isRegularFile(file), "explicit ordinary adoption keeps the original default connection behavior");
    }

    private Path prepare() throws Exception {
        Path root = Files.createDirectory(run.resolve("project"));
        open(root);
        service.closeStore("scope");
        return root;
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

    private LocalHistoryStore open(Path root) {
        return service.openStore("scope", root.resolve("history"), HistoryPolicy.defaults());
    }

    private static CaptureRequest request(String value) {
        return CaptureRequest.of(KEY, "note.md", ContentSource.bytes(value.getBytes(StandardCharsets.UTF_8)), HistorySource.APP);
    }

    private static final class ManualScheduler extends ScheduledThreadPoolExecutor {
        private final List<Runnable> maintenance = new ArrayList<>();
        ManualScheduler() { super(1); }
        @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
            maintenance.add(command);
            return super.scheduleWithFixedDelay(() -> { }, 1, 1, TimeUnit.DAYS);
        }
        void runLatest() { maintenance.get(maintenance.size() - 1).run(); }
    }
}
