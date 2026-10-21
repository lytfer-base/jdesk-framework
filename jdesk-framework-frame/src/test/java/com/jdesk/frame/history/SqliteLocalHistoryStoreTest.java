package com.jdesk.frame.history;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jdesk.core.history.CaptureRequest;
import com.jdesk.core.history.CaptureResult;
import com.jdesk.core.history.ContentSource;
import com.jdesk.core.history.DeletedEntry;
import com.jdesk.core.history.HistoryKey;
import com.jdesk.core.history.HistoryPolicy;
import com.jdesk.core.history.HistorySource;
import com.jdesk.core.history.HistoryVersion;
import com.jdesk.frame.history.HistoryTestSupport.MutableClock;
import com.jdesk.frame.storage.DatabaseHandle;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteLocalHistoryStoreTest {

    private static final HistoryKey KEY = HistoryKey.of("file", "f1");
    private static final long MINUTE = Duration.ofMinutes(1).toMillis();

    @TempDir
    Path dir;

    private MutableClock clock;
    private SqliteLocalHistoryStore store;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(HistoryTestSupport.BASE);
        store = HistoryTestSupport.openStore("history.test", dir.resolve("history"), HistoryPolicy.defaults(), clock);
    }

    private CaptureResult capture(String text, HistorySource source) {
        return store.capture(CaptureRequest.of(KEY, "notes/a.md",
                ContentSource.bytes(text.getBytes(StandardCharsets.UTF_8)), source)).join();
    }

    @Test
    void sealRunsAfterQueuedCaptures() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Path home = dir.resolve("queued");
            SqliteLocalHistoryStore queued = new SqliteLocalHistoryStore("history.queued",
                    HistoryTestSupport.openDatabase("history.queued", home), new HistoryObjectStore(home.resolve("objects")),
                    HistoryPolicy.defaults(), executor, clock);
            queued.capture(CaptureRequest.of(KEY, "a.md", ContentSource.bytes("v1".getBytes(StandardCharsets.UTF_8)),
                    HistorySource.APP)).join();
            CountDownLatch gate = new CountDownLatch(1);
            executor.submit(() -> gate.await(10, TimeUnit.SECONDS)); // Block the queue as maintenance would.
            clock.advance(10 * MINUTE);
            CompletableFuture<CaptureResult> second = queued.capture(CaptureRequest.of(KEY, "a.md",
                    ContentSource.bytes("v2".getBytes(StandardCharsets.UTF_8)), HistorySource.APP));
            Thread sealer = new Thread(() -> queued.seal(KEY));
            sealer.start();
            sealer.join(300);
            assertTrue(sealer.isAlive(), "the seal must wait for the save queued before it");
            gate.countDown();
            sealer.join(10_000);
            second.join();
            List<HistoryVersion> versions = queued.versions(KEY);
            assertEquals(2, versions.size());
            assertEquals(second.join().contentHash(), versions.get(0).objectHash());
            assertTrue(versions.get(0).sealed(), "the seal should land on the version saved before it");
        } finally {
            executor.shutdownNow();
        }
    }

    /** Purge runs after captures already queued; otherwise a queued capture would grow the history back. */
    @Test
    void purgeRunsAfterQueuedCapturesSoTheHistoryDoesNotGrowBack() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Path home = dir.resolve("purge");
            SqliteLocalHistoryStore queued = new SqliteLocalHistoryStore("history.purge",
                    HistoryTestSupport.openDatabase("history.purge", home), new HistoryObjectStore(home.resolve("objects")),
                    HistoryPolicy.defaults(), executor, clock);
            queued.capture(request(KEY, "v1")).join();
            CountDownLatch gate = new CountDownLatch(1);
            executor.submit(() -> gate.await(10, TimeUnit.SECONDS)); // Block the queue.
            CompletableFuture<CaptureResult> pending = queued.capture(request(KEY, "v2"));
            Thread purger = new Thread(() -> queued.purge(KEY));
            purger.start();
            purger.join(300);
            gate.countDown();
            purger.join(10_000);
            pending.join();

            assertEquals(List.of(), queued.versions(KEY), "no history should grow back after a purge");
            assertFalse(queued.hasHead(KEY));
        } finally {
            executor.shutdownNow();
        }
    }

    /** Likewise for rekey: a capture queued on the old key must not grow a new head there after the move. */
    @Test
    void rekeyRunsAfterQueuedCapturesSoTheOldKeyStaysEmpty() throws Exception {
        HistoryKey renamed = HistoryKey.of("file", "f2");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Path home = dir.resolve("rekey");
            SqliteLocalHistoryStore queued = new SqliteLocalHistoryStore("history.rekey",
                    HistoryTestSupport.openDatabase("history.rekey", home), new HistoryObjectStore(home.resolve("objects")),
                    HistoryPolicy.defaults(), executor, clock);
            queued.capture(request(KEY, "v1")).join();
            CountDownLatch gate = new CountDownLatch(1);
            executor.submit(() -> gate.await(10, TimeUnit.SECONDS));
            clock.advance(10 * MINUTE);
            CompletableFuture<CaptureResult> pending = queued.capture(request(KEY, "v2"));
            Thread rekeyer = new Thread(() -> queued.rekey(KEY, renamed));
            rekeyer.start();
            rekeyer.join(300);
            gate.countDown();
            rekeyer.join(10_000);
            pending.join();

            assertFalse(queued.hasHead(KEY), "the old key should not get a head again");
            assertEquals(List.of(), queued.versions(KEY));
            assertEquals(2, queued.versions(renamed).size(), "both versions should move to the new key");
        } finally {
            executor.shutdownNow();
        }
    }

    /** Labelling a version during a fold into it keeps its content; the new content gets its own version. */
    @Test
    void aVersionLabelledWhileAFoldIsInFlightKeepsItsContent() throws Exception {
        Path home = dir.resolve("label");
        DatabaseHandle real = HistoryTestSupport.openDatabase("history.label", home);
        AtomicInteger armed = new AtomicInteger();
        CountDownLatch foldDecided = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DatabaseHandle gated = (DatabaseHandle) Proxy.newProxyInstance(DatabaseHandle.class.getClassLoader(),
                new Class<?>[]{DatabaseHandle.class}, (proxy, method, args) -> {
                    if (method.getName().equals("transaction") && armed.compareAndSet(1, 0)) {
                        foldDecided.countDown();
                        release.await(10, TimeUnit.SECONDS);
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            SqliteLocalHistoryStore labelled = new SqliteLocalHistoryStore("history.label", gated,
                    new HistoryObjectStore(home.resolve("objects")), HistoryPolicy.defaults(), executor, clock);
            CaptureResult first = labelled.capture(request(KEY, "v1")).join();
            long versionId = labelled.versions(KEY).get(0).versionId();
            clock.advance(MINUTE);
            armed.set(1);
            CompletableFuture<CaptureResult> folding = labelled.capture(request(KEY, "v2"));
            assertTrue(foldDecided.await(5, TimeUnit.SECONDS), "the folding capture should be about to write");

            labelled.label(versionId, "定稿");
            release.countDown();
            folding.join();

            HistoryVersion kept = labelled.version(versionId).orElseThrow();
            assertEquals("定稿", kept.label());
            assertEquals(first.contentHash(), kept.objectHash(), "a fold must not overwrite a labelled version");
            assertEquals(2, labelled.versions(KEY).size(), "the new content should get its own version");
        } finally {
            executor.shutdownNow();
        }
    }

    private static CaptureRequest request(HistoryKey key, String text) {
        return CaptureRequest.of(key, "a.md", ContentSource.bytes(text.getBytes(StandardCharsets.UTF_8)), HistorySource.APP);
    }

    @Test
    void captureInsideRestoreActivityIsASealedRestoreVersion() {
        capture("v1", HistorySource.APP);
        long activity = store.beginActivity("", HistorySource.RESTORE, "{\"capturedAt\":1000}");
        CaptureResult restored = capture("v0", HistorySource.APP);
        store.endActivity(activity);
        HistoryVersion head = store.versions(KEY).get(0);
        assertEquals(HistorySource.RESTORE, head.source());
        assertTrue(head.sealed());
        assertEquals(Long.valueOf(activity), head.activityId());
        assertEquals("", head.activityName());
        assertEquals("{\"capturedAt\":1000}", head.activityParams(), "the parameters should read back unchanged");
        assertEquals(restored.contentHash(), head.objectHash());
        clock.advance(MINUTE);
        capture("v2", HistorySource.APP);
        assertEquals(3, store.versions(KEY).size(), "the restored version stands alone; later edits don't fold in");
        assertEquals(HistorySource.APP, store.versions(KEY).get(0).source(), "captures are APP after the activity");
    }

    @Test
    void restoreCapturedAfterItsActivityEndedIsStillARestoreVersion() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Path home = dir.resolve("queued");
            SqliteLocalHistoryStore queued = new SqliteLocalHistoryStore("history.queued",
                    HistoryTestSupport.openDatabase("history.queued", home), new HistoryObjectStore(home.resolve("objects")),
                    HistoryPolicy.defaults(), executor, clock);
            queued.capture(CaptureRequest.of(KEY, "a.md", ContentSource.bytes("before".getBytes(StandardCharsets.UTF_8)),
                    HistorySource.APP)).join();
            clock.advance(MINUTE);
            CountDownLatch gate = new CountDownLatch(1);
            executor.submit(() -> gate.await(10, TimeUnit.SECONDS)); // Block the queue like a baseline backlog.
            long activity = queued.beginActivity("", HistorySource.RESTORE, "{\"capturedAt\":1000}");
            CompletableFuture<CaptureResult> restored = queued.capture(CaptureRequest.of(KEY, "a.md",
                    ContentSource.bytes("older".getBytes(StandardCharsets.UTF_8)), HistorySource.APP));
            queued.endActivity(activity); // The restore ends its activity without waiting for the capture.
            gate.countDown();
            restored.join();
            List<HistoryVersion> versions = queued.versions(KEY);
            assertEquals(2, versions.size(), "the restored version must not fold into the one before it");
            assertEquals(HistorySource.RESTORE, versions.get(0).source());
            assertTrue(versions.get(0).sealed());
            assertEquals(Long.valueOf(activity), versions.get(0).activityId());
            assertArrayEquals("before".getBytes(StandardCharsets.UTF_8),
                    queued.content(versions.get(1).versionId()).orElseThrow(), "the pre-restore content must remain");
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Delete marks fed from the VFS are queued in order but the caller doesn't wait for them; waiting would hold the
     * shared VFS reconcile thread for seconds when a folder with thousands of files is deleted.
     */
    @Test
    void markDeletedLaterQueuesWithoutWaitingAndStillLands() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Path home = dir.resolve("later");
            SqliteLocalHistoryStore queued = new SqliteLocalHistoryStore("history.later",
                    HistoryTestSupport.openDatabase("history.later", home), new HistoryObjectStore(home.resolve("objects")),
                    HistoryPolicy.defaults(), executor, clock);
            queued.capture(CaptureRequest.of(KEY, "a.md", ContentSource.bytes("v1".getBytes(StandardCharsets.UTF_8)),
                    HistorySource.APP)).join();
            CountDownLatch gate = new CountDownLatch(1);
            executor.submit(() -> gate.await(10, TimeUnit.SECONDS)); // Block the queue like a history backlog.

            long startedAt = System.nanoTime();
            queued.markDeletedLater(KEY, clock.millis());
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

            assertTrue(elapsedMillis < 1000, "feeding must not wait for the queue; waited " + elapsedMillis + " ms");
            assertTrue(queued.deletedEntries().isEmpty(), "the queue is blocked, so nothing should be stored yet");
            gate.countDown();
            long deadline = System.currentTimeMillis() + 10_000;
            while (queued.deletedEntries().isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(1, queued.deletedEntries().size(), "the mark must land once the queue is released");
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * A batch of delete marks shares one transaction; a transaction per mark opens a connection each time, so deleting
     * a folder would cost that for every file in it.
     */
    @Test
    void aBatchOfDeleteMarksSharesOneTransaction() {
        AtomicInteger transactions = new AtomicInteger();
        Path home = dir.resolve("batch");
        SqliteLocalHistoryStore batched = new SqliteLocalHistoryStore("history.batch",
                countingTransactions(HistoryTestSupport.openDatabase("history.batch", home), transactions),
                new HistoryObjectStore(home.resolve("objects")), HistoryPolicy.defaults(), Runnable::run, clock);
        List<HistoryKey> queued = new ArrayList<>();
        List<HistoryKey> immediate = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            HistoryKey key = HistoryKey.of("file", "f" + i);
            batched.capture(CaptureRequest.of(key, "notes/a" + i + ".md",
                    ContentSource.bytes(("v" + i).getBytes(StandardCharsets.UTF_8)), HistorySource.APP)).join();
            (i < 10 ? queued : immediate).add(key);
        }
        transactions.set(0);

        batched.markDeletedLater(queued, clock.millis());
        batched.markDeleted(immediate, clock.millis());

        assertEquals(20, batched.deletedEntries().size(), "every key in a batch should be marked");
        assertEquals(2, transactions.get(), "one transaction per batch, not per key");
    }

    /**
     * Reviving a batch of files from the recycle bin reads their heads in one query and clears the delete marks in one
     * transaction; changed content still gets a new version.
     */
    @Test
    void aBatchOfRevivedFilesClearsDeleteMarksInOneTransaction() {
        AtomicInteger calls = new AtomicInteger();
        Path home = dir.resolve("revive");
        SqliteLocalHistoryStore batched = new SqliteLocalHistoryStore("history.revive",
                countingCalls(HistoryTestSupport.openDatabase("history.revive", home), calls, "transaction", "write", "read"),
                new HistoryObjectStore(home.resolve("objects")), HistoryPolicy.defaults(), Runnable::run, clock);
        List<HistoryKey> keys = new ArrayList<>();
        List<CaptureRequest> revived = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            HistoryKey key = HistoryKey.of("file", "f" + i);
            CaptureRequest request = CaptureRequest.of(key, "notes/a" + i + ".md",
                    ContentSource.bytes(("v" + i).getBytes(StandardCharsets.UTF_8)), HistorySource.EXTERNAL);
            batched.capture(request).join();
            keys.add(key);
            revived.add(request);
        }
        batched.markDeleted(keys, clock.millis());
        calls.set(0);

        batched.captureAll(revived);

        assertEquals(2, calls.get(), "one head query and one transaction, not a read and write per file");
        assertTrue(batched.deletedEntries().isEmpty(), "a file back unchanged is no longer deleted");
        assertEquals(1, batched.versions(keys.get(0)).size(), "unchanged content gets no new version");

        HistoryKey edited = keys.get(1);
        batched.markDeleted(edited, clock.millis());
        clock.advance(10 * MINUTE);
        batched.captureAll(List.of(CaptureRequest.of(edited, "notes/a1.md",
                ContentSource.bytes("changed while away".getBytes(StandardCharsets.UTF_8)), HistorySource.EXTERNAL)));
        assertTrue(batched.deletedEntries().isEmpty(), "a changed file is no longer deleted either");
        assertEquals(2, batched.versions(edited).size(), "changed content gets a new version");
    }

    /** afterQueued completes after the writes queued so far, so a reader reloading after a VFS event sees them. */
    @Test
    void afterQueuedCompletesOnlyOnceEarlierWorkHasLanded() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Path home = dir.resolve("settle");
            SqliteLocalHistoryStore queued = new SqliteLocalHistoryStore("history.settle",
                    HistoryTestSupport.openDatabase("history.settle", home), new HistoryObjectStore(home.resolve("objects")),
                    HistoryPolicy.defaults(), executor, clock);
            queued.capture(CaptureRequest.of(KEY, "a.md", ContentSource.bytes("v1".getBytes(StandardCharsets.UTF_8)),
                    HistorySource.APP)).join();
            CountDownLatch gate = new CountDownLatch(1);
            executor.submit(() -> gate.await(10, TimeUnit.SECONDS)); // Block the queue like a history backlog.
            queued.markDeletedLater(KEY, clock.millis());

            CompletableFuture<Void> settled = queued.afterQueued();

            Thread.sleep(100);
            assertFalse(settled.isDone(), "the delete mark queued earlier hasn't landed yet");
            gate.countDown();
            settled.get(10, TimeUnit.SECONDS);
            assertEquals(1, queued.deletedEntries().size(), "the earlier writes should be stored on completion");
        } finally {
            executor.shutdownNow();
        }
    }

    private static DatabaseHandle countingTransactions(DatabaseHandle real, AtomicInteger transactions) {
        return countingCalls(real, transactions, "transaction", "write");
    }

    /** Counts the named database calls (each opens a connection) and forwards every call to the real handle. */
    private static DatabaseHandle countingCalls(DatabaseHandle real, AtomicInteger calls, String... methods) {
        List<String> counted = List.of(methods);
        return (DatabaseHandle) Proxy.newProxyInstance(DatabaseHandle.class.getClassLoader(),
                new Class<?>[]{DatabaseHandle.class}, (proxy, method, args) -> {
                    if (counted.contains(method.getName())) {
                        calls.incrementAndGet();
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    @Test
    void firstCaptureCreatesVersionAndObject() {
        CaptureResult result = capture("v1", HistorySource.APP);
        assertTrue(result.captured());
        List<HistoryVersion> versions = store.versions(KEY);
        assertEquals(1, versions.size());
        HistoryVersion v = versions.get(0);
        assertEquals(result.contentHash(), v.objectHash());
        assertEquals(HistorySource.APP, v.source());
        assertEquals("notes/a.md", v.path());
        assertFalse(v.sealed());
        assertArrayEquals("v1".getBytes(StandardCharsets.UTF_8), store.content(v.versionId()).orElseThrow());
        assertTrue(store.hasHead(KEY));
    }

    @Test
    void unchangedContentIsSkipped() {
        capture("same", HistorySource.APP);
        clock.advance(MINUTE);
        CaptureResult again = capture("same", HistorySource.APP);
        assertFalse(again.captured());
        assertEquals("unchanged", again.skippedReason());
        assertEquals(1, store.versions(KEY).size());
    }

    @Test
    void appWritesWithinWindowFoldIntoHead() {
        capture("v1", HistorySource.APP);
        clock.advance(MINUTE);
        capture("v2", HistorySource.APP);
        List<HistoryVersion> versions = store.versions(KEY);
        assertEquals(1, versions.size(), "two saves within 5 minutes fold into one row");
        assertEquals(HistoryTestSupport.BASE + MINUTE, versions.get(0).capturedAt(), "the time is the last save's");
        assertArrayEquals("v2".getBytes(StandardCharsets.UTF_8), store.content(versions.get(0).versionId()).orElseThrow());
    }

    @Test
    void appWritesBeyondWindowStartNewVersion() {
        capture("v1", HistorySource.APP);
        clock.advance(6 * MINUTE);
        capture("v2", HistorySource.APP);
        List<HistoryVersion> versions = store.versions(KEY);
        assertEquals(2, versions.size());
        assertArrayEquals("v2".getBytes(StandardCharsets.UTF_8), store.content(versions.get(0).versionId()).orElseThrow());
        assertArrayEquals("v1".getBytes(StandardCharsets.UTF_8), store.content(versions.get(1).versionId()).orElseThrow());
    }

    @Test
    void externalChangeSealsAppVersion() {
        capture("user-1", HistorySource.APP);
        clock.advance(MINUTE);
        capture("user-2", HistorySource.APP);
        clock.advance(10_000);
        capture("agent", HistorySource.EXTERNAL);
        List<HistoryVersion> versions = store.versions(KEY);
        assertEquals(2, versions.size(), "an external change must not fold into the user's row");
        assertEquals(HistorySource.EXTERNAL, versions.get(0).source());
        assertArrayEquals("user-2".getBytes(StandardCharsets.UTF_8),
                store.content(versions.get(1).versionId()).orElseThrow(), "the user's last saved content must remain");
        clock.advance(10_000);
        capture("user-3", HistorySource.APP);
        assertEquals(3, store.versions(KEY).size(), "switching back to the app source starts a new row too");
    }

    @Test
    void explicitSealStopsFolding() {
        capture("v1", HistorySource.APP);
        store.seal(KEY);
        clock.advance(MINUTE);
        capture("v2", HistorySource.APP);
        assertEquals(2, store.versions(KEY).size());
        assertTrue(store.versions(KEY).get(1).sealed());
    }

    @Test
    void externalChangesGroupByIdleGap() {
        HistoryKey other = HistoryKey.of("file", "f2");
        capture("e1", HistorySource.EXTERNAL);
        clock.advance(30_000);
        store.capture(CaptureRequest.of(other, "b.md", ContentSource.bytes("x".getBytes()), HistorySource.EXTERNAL)).join();
        clock.advance(90_000);
        store.capture(CaptureRequest.of(other, "b.md", ContentSource.bytes("y".getBytes()), HistorySource.EXTERNAL)).join();
        Long first = store.versions(KEY).get(0).activityId();
        List<HistoryVersion> others = store.versions(other);
        assertNotNull(first);
        assertEquals(first, others.get(1).activityId(), "external changes within 30 seconds share an activity");
        assertNotEquals(first, others.get(0).activityId(), "over 60 seconds of quiet starts a new activity");
        assertEquals(2, others.size(), "different activities don't fold");
    }

    @Test
    void explicitActivityAttachesAppCaptures() {
        capture("before", HistorySource.APP);
        clock.advance(MINUTE);
        long activity = store.beginActivity("AI 整理", HistorySource.APP);
        capture("organized", HistorySource.APP);
        store.endActivity(activity);
        clock.advance(MINUTE);
        capture("after", HistorySource.APP);
        List<HistoryVersion> versions = store.versions(KEY);
        assertEquals(3, versions.size(), "entering and leaving the activity each seal");
        assertEquals(activity, versions.get(1).activityId());
        assertEquals("AI 整理", versions.get(1).activityName());
        assertNull(versions.get(1).activityParams(), "an activity begun without parameters reads back null");
        assertNull(versions.get(0).activityId());
    }

    @Test
    void baselineSkipsWhenHeadExists() {
        CaptureRequest request = CaptureRequest.of(KEY, "a.md", ContentSource.bytes("base".getBytes()), HistorySource.APP);
        CaptureResult first = store.captureBaseline(request).join();
        assertTrue(first.captured());
        assertEquals(HistorySource.BASELINE, store.versions(KEY).get(0).source());
        assertTrue(store.versions(KEY).get(0).sealed());
        CaptureResult second = store.captureBaseline(request).join();
        assertEquals("has-head", second.skippedReason());
    }

    @Test
    void snapshotAlwaysNewSealedRow() {
        capture("v1", HistorySource.APP);
        store.capture(new CaptureRequest(KEY, "a.md", ContentSource.bytes("v1".getBytes()), HistorySource.SNAPSHOT, "发布前")).join();
        List<HistoryVersion> versions = store.versions(KEY);
        assertEquals(1, versions.size(), "unchanged content adds no row, even for a snapshot");
        clock.advance(1000);
        store.capture(new CaptureRequest(KEY, "a.md", ContentSource.bytes("v2".getBytes()), HistorySource.SNAPSHOT, "发布前")).join();
        versions = store.versions(KEY);
        assertEquals(2, versions.size());
        assertEquals("发布前", versions.get(0).label());
        assertTrue(versions.get(0).sealed());
        clock.advance(1000);
        capture("v3", HistorySource.APP);
        assertEquals(3, store.versions(KEY).size(), "a snapshot is sealed, so later writes start a new row");
    }

    @Test
    void tooLargeRecordsEventWithoutObject() {
        HistoryPolicy small = new HistoryPolicy(Duration.ofMinutes(5), Duration.ofSeconds(60), 8, 64, 1 << 20,
                Duration.ofDays(30), HistoryPolicy.defaults().textExtensions());
        SqliteLocalHistoryStore tiny = HistoryTestSupport.openStore("history.tiny", dir.resolve("tiny"), small, clock);
        CaptureResult result = tiny.capture(CaptureRequest.of(KEY, "big.bin",
                ContentSource.bytes(new byte[32]), HistorySource.EXTERNAL)).join();
        assertFalse(result.captured());
        assertEquals("too-large", result.skippedReason());
        HistoryVersion v = tiny.versions(KEY).get(0);
        assertFalse(v.hasContent());
        assertEquals("too-large", v.skippedReason());
        assertEquals(32, v.size());
        assertTrue(tiny.content(v.versionId()).isEmpty());
    }

    @Test
    void markDeletedSealsAndListsEntry() {
        capture("v1", HistorySource.APP);
        store.markDeleted(KEY, clock.millis());
        List<DeletedEntry> entries = store.deletedEntries();
        assertEquals(1, entries.size());
        assertEquals(KEY, entries.get(0).key());
        assertEquals(clock.millis() + Duration.ofDays(30).toMillis(), entries.get(0).purgeAt());
        assertTrue(store.versions(KEY).get(0).sealed());
        store.unmarkDeleted(KEY);
        assertTrue(store.deletedEntries().isEmpty());
        clock.advance(1000);
        capture("back", HistorySource.APP);
        assertTrue(store.deletedEntries().isEmpty(), "writing again clears the delete mark too");
    }

    /**
     * Marking with paths sets the head path to where each file was deleted, which the deleted entries show; the head
     * version is still sealed and keeps its own path.
     */
    @Test
    void markingWithPathsRecordsWhereEachFileWasDeleted() {
        HistoryKey queued = HistoryKey.of("file", "f2");
        capture("v1", HistorySource.APP);
        store.capture(CaptureRequest.of(queued, "notes/b.md", ContentSource.bytes("b".getBytes(StandardCharsets.UTF_8)),
                HistorySource.APP)).join();
        long deletedAt = clock.millis();

        store.markDeleted(Map.of(KEY, "archive/a.md"), deletedAt);
        store.markDeletedLater(Map.of(queued, "archive/b.md"), deletedAt);

        assertEquals(Optional.of("archive/a.md"), store.headPath(KEY));
        assertEquals(Optional.of("archive/b.md"), store.headPath(queued));
        assertEquals(Map.of(KEY, "archive/a.md", queued, "archive/b.md"), pathsListed(store));
        assertTrue(store.deletedEntries().stream().allMatch(entry -> entry.deletedAt() == deletedAt));
        assertTrue(store.versions(KEY).get(0).sealed());
        assertEquals("notes/a.md", store.versions(KEY).get(0).path(), "the version keeps its own path");
    }

    /** Marking an already deleted key again keeps the first time and path. */
    @Test
    void markingAgainKeepsTheFirstTimeAndPath() {
        capture("v1", HistorySource.APP);
        long first = clock.millis();
        store.markDeleted(Map.of(KEY, "archive/a.md"), first);
        clock.advance(MINUTE);

        store.markDeleted(Map.of(KEY, "elsewhere/a.md"), clock.millis());

        DeletedEntry entry = store.deletedEntries().get(0);
        assertEquals("archive/a.md", entry.path());
        assertEquals(first, entry.deletedAt());
    }

    /** The overloads without paths leave the head path at the last capture's; a key without a head has none. */
    @Test
    void markingWithoutPathsLeavesTheHeadPathAtTheLastCapture() {
        List<HistoryKey> keys = new ArrayList<>();
        Map<HistoryKey, String> captured = new LinkedHashMap<>();
        for (int i = 0; i < 4; i++) {
            HistoryKey key = HistoryKey.of("file", "f" + i);
            store.capture(CaptureRequest.of(key, "notes/a" + i + ".md",
                    ContentSource.bytes(("v" + i).getBytes(StandardCharsets.UTF_8)), HistorySource.APP)).join();
            keys.add(key);
            captured.put(key, "notes/a" + i + ".md");
        }
        assertEquals(Optional.empty(), store.headPath(HistoryKey.of("file", "none")));

        store.markDeleted(keys.get(0), clock.millis());
        store.markDeleted(List.of(keys.get(1)), clock.millis());
        store.markDeletedLater(keys.get(2), clock.millis());
        store.markDeletedLater(List.of(keys.get(3)), clock.millis());

        for (HistoryKey key : keys) {
            assertEquals(Optional.of(captured.get(key)), store.headPath(key));
        }
        assertEquals(captured, pathsListed(store));
    }

    /** Paths spanning several chunks take one transaction per chunk, and every key is marked with its path. */
    @Test
    void aBatchOfPathsSpanningChunksTakesOneTransactionPerChunk() {
        AtomicInteger transactions = new AtomicInteger();
        Path home = dir.resolve("paths");
        SqliteLocalHistoryStore batched = new SqliteLocalHistoryStore("history.paths",
                countingTransactions(HistoryTestSupport.openDatabase("history.paths", home), transactions),
                new HistoryObjectStore(home.resolve("objects")), HistoryPolicy.defaults(), Runnable::run, clock);
        int chunk = SqliteLocalHistoryStore.KEY_CHUNK;
        Map<HistoryKey, String> paths = new LinkedHashMap<>();
        for (int i = 0; i <= 2 * chunk; i++) {
            paths.put(HistoryKey.of("file", "f" + i), "archive/a" + i + ".md");
        }
        List<HistoryKey> captured = List.of(HistoryKey.of("file", "f0"), HistoryKey.of("file", "f" + chunk),
                HistoryKey.of("file", "f" + 2 * chunk));
        for (HistoryKey key : captured) {
            batched.capture(CaptureRequest.of(key, "notes/" + key.id() + ".md",
                    ContentSource.bytes(key.id().getBytes(StandardCharsets.UTF_8)), HistorySource.APP)).join();
        }
        transactions.set(0);

        batched.markDeleted(paths, clock.millis());

        assertEquals(3, transactions.get(), "one transaction per chunk");
        for (HistoryKey key : captured) {
            assertEquals(Optional.of(paths.get(key)), batched.headPath(key));
        }
        assertEquals(3, batched.deletedEntries().size());
    }

    private static Map<HistoryKey, String> pathsListed(SqliteLocalHistoryStore store) {
        return store.deletedEntries().stream().collect(Collectors.toMap(DeletedEntry::key, DeletedEntry::path));
    }

    @Test
    void unchangedCaptureAfterDeletionClearsTheMark() {
        capture("v1", HistorySource.APP);
        store.markDeleted(KEY, clock.millis());
        clock.advance(1000);
        CaptureResult again = capture("v1", HistorySource.APP);
        assertEquals("unchanged", again.skippedReason());
        assertTrue(store.deletedEntries().isEmpty(), "unchanged content adds no version but is no longer deleted");
    }

    @Test
    void labelAndPurge() {
        capture("v1", HistorySource.APP);
        long id = store.versions(KEY).get(0).versionId();
        store.label(id, " 重要 ");
        assertEquals("重要", store.version(id).orElseThrow().label());
        assertTrue(store.objectHashes("file").contains(store.version(id).orElseThrow().objectHash()));
        store.purge(KEY);
        assertTrue(store.versions(KEY).isEmpty());
        assertFalse(store.hasHead(KEY));
        assertEquals(0, store.stats().versionCount());
    }

    @Test
    void rekeyMovesHistoryAndClearsDeletion() {
        capture("v1", HistorySource.APP);
        store.markDeleted(KEY, clock.millis());
        HistoryKey fresh = HistoryKey.of("file", "f9");
        store.rekey(KEY, fresh);
        assertTrue(store.versions(KEY).isEmpty());
        assertEquals(1, store.versions(fresh).size());
        assertEquals(fresh, store.versions(fresh).get(0).key());
        assertTrue(store.deletedEntries().isEmpty(), "after the move it is no longer a deleted entry");
        assertTrue(store.hasHead(fresh));
    }

    /**
     * Rekeying onto the same key, as when a restore revives the same identity at the same path, only clears the delete
     * mark and keeps the head's path, hash and version; a key without a head is left alone.
     */
    @Test
    void rekeyOntoTheSameKeyOnlyClearsTheDeletionMark() {
        capture("v1", HistorySource.APP);
        clock.advance(10 * MINUTE);
        capture("v2", HistorySource.APP);
        store.markDeleted(Map.of(KEY, "archive/a.md"), clock.millis());
        SqliteLocalHistoryStore.Head before = store.findHead(KEY).orElseThrow();
        assertNotNull(before.deletedAt(), "precondition: marked deleted");

        store.rekey(KEY, KEY);

        assertEquals(Optional.of(new SqliteLocalHistoryStore.Head(before.path(), before.headVersionId(),
                before.contentHash(), null)), store.findHead(KEY));
        assertEquals(2, store.versions(KEY).size());
        assertTrue(store.deletedEntries().isEmpty(), "no longer listed as deleted");
        HistoryKey headless = HistoryKey.of("file", "none");
        store.rekey(headless, headless);
        assertFalse(store.hasHead(headless));
    }

    @Test
    void diskSourceReadsFileAndMarksEolOnlyChanges() throws Exception {
        Path file = dir.resolve("doc.md");
        Files.writeString(file, "a\nb\n");
        CaptureResult first = store.capture(CaptureRequest.of(KEY, "doc.md", ContentSource.disk(file), HistorySource.EXTERNAL)).join();
        assertTrue(first.captured());
        clock.advance(6 * MINUTE);
        Files.writeString(file, "a\r\nb\r\n");
        store.capture(CaptureRequest.of(KEY, "doc.md", ContentSource.disk(file), HistorySource.EXTERNAL)).join();
        List<HistoryVersion> versions = store.versions(KEY);
        assertEquals(2, versions.size());
        assertNotEquals(versions.get(0).objectHash(), versions.get(1).objectHash());
        assertEquals(versions.get(0).normHash(), versions.get(1).normHash(), "EOL-only changes share a norm hash");
        assertEquals("missing", store.capture(CaptureRequest.of(KEY, "gone.md",
                ContentSource.disk(dir.resolve("gone.md")), HistorySource.EXTERNAL)).join().skippedReason());
    }
}
