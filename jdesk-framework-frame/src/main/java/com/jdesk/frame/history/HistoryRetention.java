package com.jdesk.frame.history;

import com.jdesk.core.history.HistoryPolicy;
import com.jdesk.frame.storage.DatabaseHandle;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Retention for a history database: progressive thinning, purging expired deleted entries, a total size cap and a
 * sweep of unreferenced objects, with one transaction per key so no lock is held for long. Labeled and head versions
 * survive thinning and the cap, but an expired deleted entry is purged with its labeled versions.
 * If you change these exemptions, check {@code HistoryRetentionTest}.
 */
final class HistoryRetention {

    private static final Logger LOG = LoggerFactory.getLogger(HistoryRetention.class);
    private static final long HOUR = Duration.ofHours(1).toMillis();
    private static final long DAY = Duration.ofDays(1).toMillis();
    private static final long RECENT = Duration.ofHours(24).toMillis();
    private static final long HOURLY_UNTIL = Duration.ofDays(30).toMillis();

    private final DatabaseHandle database;
    private final HistoryObjectStore objects;
    private final HistoryPolicy policy;
    private final Clock clock;
    /**
     * The lock captures hold; take it for {@link #sweep()} and nothing else, since a capture landing between picking
     * orphans and deleting them could reference one. Thinning and purging work per key and needn't block captures.
     */
    private final Object sweepLock;

    HistoryRetention(DatabaseHandle database, HistoryObjectStore objects, HistoryPolicy policy, Clock clock,
                     Object sweepLock) {
        this.database = Objects.requireNonNull(database, "database");
        this.objects = Objects.requireNonNull(objects, "objects");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sweepLock = Objects.requireNonNull(sweepLock, "sweepLock");
    }

    void run(long now) {
        thinAll(now);
        purgeExpiredDeleted(now);
        sweep();
        enforceCap();
    }

    private record KeyRef(String kind, String id) {
    }

    private record Row(long versionId, long capturedAt, boolean labeled) {
    }

    /** Thins each key that has two or more versions past the recent window, selected up front by one query. */
    void thinAll(long now) {
        List<KeyRef> keys = database.read(session -> session.queryList("""
                select key_kind, key_id from lh_versions where captured_at <= ?
                 group by key_kind, key_id having count(*) > 1
                """, row -> new KeyRef(row.getString("key_kind"), row.getString("key_id")), now - RECENT));
        for (KeyRef key : keys) {
            thin(key, now);
        }
    }

    private void thin(KeyRef key, long now) {
        database.transaction(tx -> {
            Long headId = tx.queryOne("select head_version_id from lh_heads where key_kind = ? and key_id = ?",
                    row -> row.getLong("head_version_id"), key.kind(), key.id()).orElse(null);
            List<Row> rows = tx.queryList("""
                    select version_id, captured_at, label from lh_versions
                     where key_kind = ? and key_id = ? order by captured_at desc, version_id desc
                    """, row -> new Row(row.getLong("version_id"), row.getLong("captured_at"),
                            row.getString("label") != null), key.kind(), key.id());
            Set<String> seenBuckets = new HashSet<>();
            List<Long> doomed = new ArrayList<>();
            ZoneId zone = clock.getZone();
            for (Row row : rows) {
                long age = now - row.capturedAt();
                if (age < RECENT) {
                    continue;
                }
                // Head and labeled versions survive but still claim their bucket, so older ones in it get thinned.
                boolean exempt = row.labeled() || (headId != null && headId == row.versionId());
                String bucket = age < HOURLY_UNTIL
                        ? "h" + (row.capturedAt() / HOUR)
                        : "d" + Instant.ofEpochMilli(row.capturedAt()).atZone(zone).toLocalDate();
                if (!seenBuckets.add(bucket) && !exempt) {
                    doomed.add(row.versionId());
                }
            }
            for (Long id : doomed) {
                tx.update("delete from lh_versions where version_id = ?", id);
            }
            return null;
        });
    }

    void purgeExpiredDeleted(long now) {
        long retention = policy.deletedRetention().toMillis();
        List<KeyRef> expired = database.read(session -> session.queryList(
                "select key_kind, key_id from lh_heads where deleted_at is not null and deleted_at + ? < ?",
                row -> new KeyRef(row.getString("key_kind"), row.getString("key_id")), retention, now));
        for (KeyRef key : expired) {
            purgeIfStillExpired(key.kind(), key.id(), now);
        }
    }

    /** Purges an expired deleted key, rechecking in the same transaction so a key that came back meanwhile survives. */
    void purgeIfStillExpired(String kind, String id, long now) {
        long retention = policy.deletedRetention().toMillis();
        database.transaction(tx -> {
            boolean stillExpired = tx.queryOne("""
                    select 1 as hit from lh_heads
                     where key_kind = ? and key_id = ? and deleted_at is not null and deleted_at + ? < ?
                    """, row -> Boolean.TRUE, kind, id, retention, now).isPresent();
            if (!stillExpired) {
                return null;
            }
            // Labeled versions go too: a label only protects versions while the entry exists.
            tx.update("delete from lh_versions where key_kind = ? and key_id = ?", kind, id);
            tx.update("delete from lh_heads where key_kind = ? and key_id = ?", kind, id);
            return null;
        });
    }

    void enforceCap() {
        long cap = policy.libraryCapBytes();
        while (storedBytes() > cap) {
            List<Long> batch = database.read(session -> session.queryList("""
                    select version_id from lh_versions
                     where label is null and version_id not in (select head_version_id from lh_heads)
                     order by captured_at asc, version_id asc limit 50
                    """, row -> row.getLong("version_id")));
            if (batch.isEmpty()) {
                LOG.warn("history.cap.exhausted stored={} cap={}", storedBytes(), cap);
                return;
            }
            database.transaction(tx -> {
                for (Long id : batch) {
                    tx.update("delete from lh_versions where version_id = ?", id);
                }
                return null;
            });
            sweep();
        }
    }

    void sweep() {
        synchronized (sweepLock) {
            sweepLocked();
        }
    }

    private void sweepLocked() {
        objects.sweepTempFiles();
        List<String> orphans = database.read(session -> session.queryList("""
                select hash from lh_objects
                 where hash not in (select object_hash from lh_versions where object_hash is not null)
                """, row -> row.getString("hash")));
        for (String hash : orphans) {
            objects.delete(hash);
        }
        if (!orphans.isEmpty()) {
            database.transaction(tx -> {
                for (String hash : orphans) {
                    tx.update("delete from lh_objects where hash = ?", hash);
                }
                return null;
            });
        }
    }

    long storedBytes() {
        return database.read(session -> session.queryOne(
                "select coalesce(sum(stored_size), 0) as b from lh_objects", row -> row.getLong("b")).orElse(0L));
    }
}
