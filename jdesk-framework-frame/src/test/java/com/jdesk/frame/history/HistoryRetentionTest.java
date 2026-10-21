package com.jdesk.frame.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jdesk.core.history.CaptureRequest;
import com.jdesk.core.history.ContentSource;
import com.jdesk.core.history.HistoryKey;
import com.jdesk.core.history.HistoryPolicy;
import com.jdesk.core.history.HistorySource;
import com.jdesk.core.history.HistoryVersion;
import com.jdesk.frame.history.HistoryTestSupport.MutableClock;
import com.jdesk.frame.storage.DatabaseHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoryRetentionTest {

    private static final HistoryKey KEY = HistoryKey.of("file", "f1");
    private static final long HOUR = Duration.ofHours(1).toMillis();
    private static final long DAY = Duration.ofDays(1).toMillis();
    private static final long NOW = HistoryTestSupport.BASE;

    @TempDir
    Path dir;

    private MutableClock clock;
    private SqliteLocalHistoryStore store;
    private int counter;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        store = HistoryTestSupport.openStore("history.retention", dir.resolve("history"), HistoryPolicy.defaults(), clock);
    }

    /** Captures distinct content at the given time as a SNAPSHOT, which never folds, to lay out versions in time. */
    private HistoryVersion snapshotAt(long at, HistoryKey key) {
        clock.set(at);
        String text = "content-" + (counter++);
        store.capture(CaptureRequest.of(key, "a.md", ContentSource.bytes(text.getBytes(StandardCharsets.UTF_8)),
                HistorySource.SNAPSHOT)).join();
        return store.versions(key).get(0);
    }

    private HistoryVersion snapshotAt(long at) {
        return snapshotAt(at, KEY);
    }

    private List<Long> ids(HistoryKey key) {
        return store.versions(key).stream().map(HistoryVersion::versionId).toList();
    }

    @Test
    void recentDayKeepsEverything() {
        long a = snapshotAt(NOW - 23 * HOUR).versionId();
        long b = snapshotAt(NOW - 23 * HOUR + 60_000).versionId();
        long c = snapshotAt(NOW - 60_000).versionId();
        clock.set(NOW);
        store.maintain();
        assertEquals(List.of(c, b, a), ids(KEY));
    }

    @Test
    void hourBucketsKeepLatest() {
        long early = snapshotAt(NOW - 26 * HOUR).versionId();
        long late = snapshotAt(NOW - 26 * HOUR + 20 * 60_000).versionId();
        long head = snapshotAt(NOW - 60_000).versionId();
        clock.set(NOW);
        store.maintain();
        assertEquals(List.of(head, late), ids(KEY), "an hour bucket keeps only its latest version");
        assertFalse(ids(KEY).contains(early));
    }

    @Test
    void dayBucketsKeepLatest() {
        long early = snapshotAt(NOW - 40 * DAY).versionId();
        long late = snapshotAt(NOW - 40 * DAY + 3 * HOUR).versionId();
        long head = snapshotAt(NOW - 60_000).versionId();
        clock.set(NOW);
        store.maintain();
        assertEquals(List.of(head, late), ids(KEY), "after 30 days a day keeps only its latest version");
        assertFalse(ids(KEY).contains(early));
    }

    @Test
    void labelSurvivesThinning() {
        long early = snapshotAt(NOW - 26 * HOUR).versionId();
        store.label(early, "留着");
        long late = snapshotAt(NOW - 26 * HOUR + 20 * 60_000).versionId();
        long head = snapshotAt(NOW - 60_000).versionId();
        clock.set(NOW);
        store.maintain();
        assertEquals(List.of(head, late, early), ids(KEY), "labelled versions are never thinned");
    }

    @Test
    void headSurvivesThinning() {
        snapshotAt(NOW - 40 * DAY);
        long head = snapshotAt(NOW - 40 * DAY + 3 * HOUR).versionId();
        clock.set(NOW);
        store.maintain();
        assertTrue(ids(KEY).contains(head));
        assertEquals(1, ids(KEY).size());
    }

    @Test
    void expiredDeletedPurgedIncludingLabels() {
        HistoryKey gone = HistoryKey.of("file", "gone");
        HistoryKey labeled = HistoryKey.of("file", "labeled");
        snapshotAt(NOW - 40 * DAY, gone);
        long keep = snapshotAt(NOW - 40 * DAY, labeled).versionId();
        store.label(keep, "重要");
        snapshotAt(NOW - 39 * DAY, labeled);
        store.markDeleted(gone, NOW - 31 * DAY);
        store.markDeleted(labeled, NOW - 31 * DAY);
        HistoryKey fresh = HistoryKey.of("file", "fresh");
        long freshLabeled = snapshotAt(NOW - 2 * DAY, fresh).versionId();
        store.label(freshLabeled, "期内");
        store.markDeleted(fresh, NOW - DAY);
        clock.set(NOW);
        store.maintain();
        assertTrue(store.versions(gone).isEmpty(), "expired without labels: purged entirely");
        assertFalse(store.hasHead(gone));
        assertTrue(store.versions(labeled).isEmpty(), "expired with labels: purged entirely too");
        assertFalse(store.hasHead(labeled));
        assertEquals(1, store.deletedEntries().size(), "only the unexpired entry remains");
        // The same content written back after the purge is a new version, not "unchanged".
        var again = store.capture(CaptureRequest.of(labeled, "a.md", ContentSource.bytes("content-2".getBytes(StandardCharsets.UTF_8)),
                HistorySource.SNAPSHOT)).join();
        assertTrue(again.captured(), "purged content coming back needs a new version, got: " + again.skippedReason());
        assertEquals(1, store.versions(labeled).size());
        assertEquals(List.of(freshLabeled), ids(fresh), "an unexpired entry stays as is, label included");
    }

    /** A key that came back after the expiry listing isn't purged, even if deleted again since, until it expires. */
    @Test
    void aKeyThatCameBackAfterListingIsNotPurged() {
        DatabaseHandle database = HistoryTestSupport.openDatabase("history.retention.race", dir.resolve("race"));
        SqliteLocalHistoryStore raced = new SqliteLocalHistoryStore("history.retention.race", database,
                new HistoryObjectStore(dir.resolve("race").resolve("objects")), HistoryPolicy.defaults(), Runnable::run, clock);
        HistoryKey back = HistoryKey.of("file", "back");
        HistoryKey deletedAgain = HistoryKey.of("file", "deletedAgain");
        clock.set(NOW - 40 * DAY);
        raced.capture(CaptureRequest.of(back, "a.md", ContentSource.bytes("a".getBytes(StandardCharsets.UTF_8)),
                HistorySource.SNAPSHOT)).join();
        raced.capture(CaptureRequest.of(deletedAgain, "b.md", ContentSource.bytes("b".getBytes(StandardCharsets.UTF_8)),
                HistorySource.SNAPSHOT)).join();
        raced.markDeleted(back, NOW - 31 * DAY);
        raced.markDeleted(deletedAgain, NOW - 31 * DAY);
        raced.unmarkDeleted(back);
        raced.unmarkDeleted(deletedAgain);
        raced.markDeleted(deletedAgain, NOW - DAY);
        clock.set(NOW);
        HistoryRetention retention = new HistoryRetention(database, new HistoryObjectStore(dir.resolve("race").resolve("objects")),
                HistoryPolicy.defaults(), clock, new Object());

        retention.purgeIfStillExpired(back.kind(), back.id(), NOW);
        retention.purgeIfStillExpired(deletedAgain.kind(), deletedAgain.id(), NOW);

        assertEquals(1, raced.versions(back).size(), "came back: the whole history stays");
        assertTrue(raced.hasHead(back));
        assertEquals(1, raced.versions(deletedAgain).size(), "deleted again but not expired: kept");
    }

    @Test
    void capEvictsOldestUnlabeled() {
        HistoryPolicy tight = new HistoryPolicy(Duration.ofMinutes(5), Duration.ofSeconds(60), 1 << 20, 64, 120,
                Duration.ofDays(30), HistoryPolicy.defaults().textExtensions());
        SqliteLocalHistoryStore small = HistoryTestSupport.openStore("history.cap", dir.resolve("cap"), tight, clock);
        for (int i = 0; i < 6; i++) {
            clock.set(NOW - (6 - i) * HOUR);
            byte[] data = new byte[40];
            new java.util.Random(i).nextBytes(data);
            small.capture(CaptureRequest.of(KEY, "a.md", ContentSource.bytes(data), HistorySource.SNAPSHOT)).join();
        }
        List<Long> before = small.versions(KEY).stream().map(HistoryVersion::versionId).toList();
        assertEquals(6, before.size());
        clock.set(NOW);
        small.maintain();
        List<Long> after = small.versions(KEY).stream().map(HistoryVersion::versionId).toList();
        assertTrue(after.size() < 6, "over the size cap, the oldest go first");
        assertEquals(before.get(0), after.get(0), "the head version always stays");
        assertTrue(small.stats().objectBytes() <= 120);
    }

    @Test
    void sweepDeletesUnreferencedObjects() {
        clock.set(NOW - 60_000);
        String first = store.capture(CaptureRequest.of(KEY, "a.md", ContentSource.bytes("one".getBytes()),
                HistorySource.APP)).join().contentHash();
        clock.set(NOW - 30_000);
        String second = store.capture(CaptureRequest.of(KEY, "a.md", ContentSource.bytes("two".getBytes()),
                HistorySource.APP)).join().contentHash();
        HistoryObjectStore objects = new HistoryObjectStore(dir.resolve("history").resolve("objects"));
        assertTrue(objects.exists(first), "a fold only moves the pointer; the old object waits for the sweep");
        clock.set(NOW);
        store.maintain();
        assertFalse(objects.exists(first));
        assertTrue(objects.exists(second));
    }
}
