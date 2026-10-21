package com.jdesk.frame.history;

import com.jdesk.core.history.HistoryPolicy;
import com.jdesk.frame.storage.DatabaseDescriptor;
import com.jdesk.frame.storage.DatabaseHandle;
import com.jdesk.frame.storage.DatabaseMigration;
import com.jdesk.frame.storage.DatabaseOwner;
import com.jdesk.frame.storage.sqlite.SqliteDatabaseService;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared helpers for history tests: a settable clock, a database with the v01 schema in a temp directory and a store
 * running on a synchronous executor.
 */
final class HistoryTestSupport {

    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 2026-09-06T10:00:00Z, on the hour, which keeps hour buckets easy to work out. */
    static final long BASE = 1788688800000L;

    private HistoryTestSupport() {
    }

    static final class MutableClock extends Clock {
        private final AtomicLong millis;

        MutableClock(long millis) {
            this.millis = new AtomicLong(millis);
        }

        void set(long value) {
            millis.set(value);
        }

        void advance(long delta) {
            millis.addAndGet(delta);
        }

        @Override
        public ZoneId getZone() {
            return ZONE;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }
    }

    static DatabaseHandle openDatabase(String id, Path dir) {
        DatabaseDescriptor.Builder builder = DatabaseDescriptor.builder(id)
                .owner(DatabaseOwner.application("tests"))
                .name("history")
                .location(dir);
        for (DatabaseMigration migration : HistorySchema.migrations()) {
            builder.migration(migration);
        }
        return new SqliteDatabaseService().open(builder.build());
    }

    static SqliteLocalHistoryStore openStore(String id, Path dir, HistoryPolicy policy, Clock clock) {
        return new SqliteLocalHistoryStore(id, openDatabase(id, dir), new HistoryObjectStore(dir.resolve("objects")),
                policy, Runnable::run, clock);
    }
}
