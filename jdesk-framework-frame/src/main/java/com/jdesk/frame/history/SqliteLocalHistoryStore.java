package com.jdesk.frame.history;

import com.jdesk.core.history.CaptureRequest;
import com.jdesk.core.history.CaptureResult;
import com.jdesk.core.history.ContentSource;
import com.jdesk.core.history.DeletedEntry;
import com.jdesk.core.history.HistoryKey;
import com.jdesk.core.history.HistoryPolicy;
import com.jdesk.core.history.HistorySource;
import com.jdesk.core.history.HistoryStats;
import com.jdesk.core.history.HistoryVersion;
import com.jdesk.core.history.LocalHistoryStore;
import com.jdesk.frame.storage.DatabaseHandle;
import com.jdesk.frame.storage.DatabaseRow;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local history for one scope: the version log lives in {@code history.sqlite} and contents in
 * {@link HistoryObjectStore}. Captures run on the injected executor, which must run tasks one at a time in order, and
 * queries on the calling thread. Seals and delete marks join the same queue and wait for it, so a seal lands after
 * the captures queued before it. Maintenance is delegated to {@link HistoryRetention}.
 */
public final class SqliteLocalHistoryStore implements LocalHistoryStore {

    private static final Logger LOG = LoggerFactory.getLogger(SqliteLocalHistoryStore.class);
    private static final int SNIFF_BYTES = 8192;
    static final String EXTERNAL_ACTIVITY_NAME = "";

    private static final String VERSION_COLUMNS = """
            v.version_id, v.key_kind, v.key_id, v.path, v.object_hash, v.norm_hash, v.size, v.captured_at,
                   v.source, v.activity_id, a.name as activity_name, a.params as activity_params, v.label, v.sealed,
                   v.skipped_reason
            """;
    private static final String VERSION_SELECT = "select " + VERSION_COLUMNS
            + " from lh_versions v left join lh_activities a on a.activity_id = v.activity_id ";

    private final String scopeId;
    private final DatabaseHandle database;
    private final HistoryObjectStore objects;
    private final HistoryPolicy policy;
    private final Executor executor;
    private final Clock clock;
    private final HistoryRetention retention;
    /** Keeps captures out of the sweep, which would otherwise delete an object a capture referenced in between. */
    private final Object writeLock = new Object();
    /** How long a queued write waits at most; a timeout is only logged and the operation stays queued. */
    private static final long ORDERED_WAIT_SECONDS = 5;
    /** Maximum keys per transaction or {@code in} query, so huge deletions don't become one giant transaction. */
    static final int KEY_CHUNK = 500;
    private static final ThreadLocal<Boolean> ON_EXECUTOR = ThreadLocal.withInitial(() -> false);

    private final AtomicReference<Activity> currentActivity = new AtomicReference<>();
    /** Queued operations submitted but not finished; closing waits on it so queued delete marks aren't lost. */
    private int pending;
    private final Object pendingLock = new Object();
    private Long externalActivityId;
    private long externalLastCaptureAt;

    public SqliteLocalHistoryStore(String scopeId, DatabaseHandle database, HistoryObjectStore objects,
                                   HistoryPolicy policy, Executor executor, Clock clock) {
        this.scopeId = Objects.requireNonNull(scopeId, "scopeId");
        this.database = Objects.requireNonNull(database, "database");
        this.objects = Objects.requireNonNull(objects, "objects");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retention = new HistoryRetention(database, objects, policy, clock, writeLock);
    }

    @Override
    public String scopeId() {
        return scopeId;
    }

    @Override
    public HistoryPolicy policy() {
        return policy;
    }

    @Override
    public CompletableFuture<CaptureResult> capture(CaptureRequest request) {
        return submitCapture(request, false);
    }

    @Override
    public CompletableFuture<CaptureResult> captureBaseline(CaptureRequest request) {
        return submitCapture(request, true);
    }

    /**
     * Captures the batch in one queued task, fetching heads in bulk and clearing the delete marks of unchanged files
     * together. Don't capture one by one: each capture opens its own connection.
     */
    @Override
    public void captureAll(Collection<CaptureRequest> requests) {
        List<CaptureRequest> batch = List.copyOf(requests);
        if (batch.isEmpty()) {
            return;
        }
        Activity activity = currentActivity.get();
        beginPending();
        try {
            CompletableFuture.runAsync(() -> {
                try {
                    onExecutor(() -> {
                        captureBatch(batch, activity);
                        return null;
                    });
                } finally {
                    endPending();
                }
            }, executor);
        } catch (RuntimeException rejected) {
            endPending();
            throw rejected;
        }
    }

    private void captureBatch(List<CaptureRequest> batch, Activity activity) {
        Map<HistoryKey, Head> heads = findHeads(batch);
        Set<HistoryKey> seen = new HashSet<>();
        List<HistoryKey> revived = new ArrayList<>();
        for (CaptureRequest request : batch) {
            HistoryKey key = request.key();
            try {
                synchronized (writeLock) {
                    // A key seen earlier in this batch has a stale prefetched head; query it again.
                    Head head = seen.add(key) ? heads.get(key) : findHead(key).orElse(null);
                    doCaptureLocked(request, false, activity, head, revived);
                }
            } catch (RuntimeException failure) {
                LOG.warn("history.capture.failed scope={} key={} path={}", scopeId, key, request.path(), failure);
            }
            if (revived.size() >= KEY_CHUNK) {
                unmarkNow(revived);
            }
        }
        unmarkNow(revived);
    }

    /** Heads for a batch, queried {@link #KEY_CHUNK} keys at a time; keys without a head are absent. */
    private Map<HistoryKey, Head> findHeads(List<CaptureRequest> batch) {
        Map<String, List<String>> idsByKind = new HashMap<>();
        for (CaptureRequest request : batch) {
            idsByKind.computeIfAbsent(request.key().kind(), kind -> new ArrayList<>()).add(request.key().id());
        }
        Map<HistoryKey, Head> heads = new HashMap<>();
        idsByKind.forEach((kind, ids) -> {
            List<String> distinct = ids.stream().distinct().toList();
            for (int from = 0; from < distinct.size(); from += KEY_CHUNK) {
                List<String> chunk = distinct.subList(from, Math.min(from + KEY_CHUNK, distinct.size()));
                List<Object> params = new ArrayList<>(chunk.size() + 1);
                params.add(kind);
                params.addAll(chunk);
                database.read(session -> session.queryList(
                        "select key_id, path, head_version_id, content_hash, deleted_at from lh_heads where key_kind = ?"
                                + " and key_id in (" + String.join(",", Collections.nCopies(chunk.size(), "?")) + ")",
                        row -> Map.entry(new HistoryKey(kind, row.getString("key_id")), new Head(row.getString("path"),
                                row.getLong("head_version_id"), row.getString("content_hash"),
                                nullableLong(row, "deleted_at"))),
                        params.toArray())).forEach(entry -> heads.put(entry.getKey(), entry.getValue()));
            }
        });
        return heads;
    }

    /** Clears the delete marks of {@code keys} in one transaction, then empties the list. */
    private void unmarkNow(List<HistoryKey> keys) {
        if (keys.isEmpty()) {
            return;
        }
        List<HistoryKey> chunk = List.copyOf(keys);
        keys.clear();
        try {
            synchronized (writeLock) {
                database.transaction(tx -> {
                    for (HistoryKey key : chunk) {
                        tx.update("update lh_heads set deleted_at = null where key_kind = ? and key_id = ?",
                                key.kind(), key.id());
                    }
                    return null;
                });
            }
        } catch (RuntimeException failure) {
            if (database.isDisposed()) {
                LOG.debug("history.unmark.afterClose scope={} keys={}", scopeId, chunk.size());
            } else {
                LOG.warn("history.unmark.failed scope={} keys={}", scopeId, chunk.size(), failure);
            }
        }
    }

    /** Queues an empty task, which runs once all earlier work is done, since the executor runs tasks in order. */
    @Override
    public CompletableFuture<Void> afterQueued() {
        try {
            return CompletableFuture.runAsync(() -> { }, executor);
        } catch (RejectedExecutionException closed) {
            return CompletableFuture.completedFuture(null);
        }
    }

    /** Must read the current activity at submit time: a restore ends its activity before its captures run. */
    private CompletableFuture<CaptureResult> submitCapture(CaptureRequest request, boolean baseline) {
        Objects.requireNonNull(request, "request");
        Activity activity = currentActivity.get();
        beginPending();
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return onExecutor(() -> guarded(request, baseline, activity));
                } finally {
                    endPending();
                }
            }, executor);
        } catch (RuntimeException rejected) {
            endPending();
            throw rejected;
        }
    }

    private void beginPending() {
        synchronized (pendingLock) {
            pending++;
        }
    }

    private void endPending() {
        synchronized (pendingLock) {
            pending--;
            if (pending <= 0) {
                pendingLock.notifyAll();
            }
        }
    }

    /** Waits, bounded, for this store's queued work; on timeout it logs one summary and the rest may be lost. */
    @Override
    public boolean awaitPending(Duration timeout) {
        long deadlineNanos = System.nanoTime() + Objects.requireNonNull(timeout, "timeout").toNanos();
        synchronized (pendingLock) {
            while (pending > 0) {
                long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
                if (remainingMillis <= 0) {
                    LOG.warn("history.pending.left scope={} pending={} seconds={}"
                            + " (these captures and delete marks will not be persisted)",
                            scopeId, pending, timeout.toSeconds());
                    return false;
                }
                try {
                    pendingLock.wait(remainingMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    private static <T> T onExecutor(Supplier<T> task) {
        ON_EXECUTOR.set(true);
        try {
            return task.get();
        } finally {
            ON_EXECUTOR.set(false);
        }
    }

    /**
     * Queues {@code op} behind the captures under the write lock; on the executor thread, or once the executor has
     * shut down, it runs in place and returns {@code null}.
     */
    private CompletableFuture<Void> submitOrdered(Runnable op) {
        if (ON_EXECUTOR.get()) {
            synchronized (writeLock) {
                op.run();
            }
            return null;
        }
        beginPending();
        try {
            return CompletableFuture.runAsync(() -> {
                try {
                    onExecutor(() -> {
                        synchronized (writeLock) {
                            op.run();
                        }
                        return null;
                    });
                } finally {
                    endPending();
                }
            }, executor);
        } catch (RejectedExecutionException rejected) {
            try {
                synchronized (writeLock) {
                    op.run();
                }
            } finally {
                endPending();
            }
            return null;
        }
    }

    /** Runs {@code op} after the queued captures and waits for it, so a seal lands after the save queued before it. */
    private void ordered(Runnable op) {
        CompletableFuture<Void> done = submitOrdered(op);
        if (done == null) {
            return;
        }
        try {
            done.get(ORDERED_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof RuntimeException runtime ? runtime : new IllegalStateException(cause);
        } catch (TimeoutException e) {
            LOG.warn("history.ordered.slow scope={}", scopeId);
        }
    }

    /** Queues {@code op} without waiting, for callers on shared threads that can't block; failures are logged here. */
    private void orderedLater(Runnable op) {
        CompletableFuture<Void> done = submitOrdered(op);
        if (done == null) {
            return;
        }
        done.exceptionally(failure -> {
            if (database.isDisposed()) {
                // Ran after close, which history.pending.left already summarizes; don't log a stack trace for each.
                LOG.debug("history.ordered.afterClose scope={}", scopeId);
            } else {
                LOG.warn("history.ordered.failed scope={}", scopeId, failure);
            }
            return null;
        });
    }

    private CaptureResult guarded(CaptureRequest request, boolean baseline, Activity activity) {
        try {
            return doCapture(request, baseline, activity);
        } catch (RuntimeException failure) {
            LOG.warn("history.capture.failed scope={} key={} path={}", scopeId, request.key(), request.path(), failure);
            return CaptureResult.skipped("failed");
        }
    }

    private CaptureResult doCapture(CaptureRequest request, boolean baseline, Activity activity) {
        synchronized (writeLock) {
            return doCaptureLocked(request, baseline, activity);
        }
    }

    private CaptureResult doCaptureLocked(CaptureRequest request, boolean baseline, Activity activity) {
        return doCaptureLocked(request, baseline, activity, findHead(request.key()).orElse(null), null);
    }

    /** With non-null {@code deferredUnmarks}, an unchanged file only adds its key there for the batch to unmark. */
    private CaptureResult doCaptureLocked(CaptureRequest request, boolean baseline, Activity activity,
                                          Head head, List<HistoryKey> deferredUnmarks) {
        HistoryKey key = request.key();
        if (baseline && head != null) {
            return CaptureResult.skipped("has-head");
        }
        HistorySource requested = baseline ? HistorySource.BASELINE : request.source();
        // An app write during a restore activity is the restore itself: its own sealed row, not an ordinary edit.
        boolean restoring = activity != null && activity.source() == HistorySource.RESTORE;
        HistorySource source = requested == HistorySource.APP && restoring ? HistorySource.RESTORE : requested;
        long now = clock.millis();
        Loaded loaded = load(request);
        if (loaded == null) {
            return CaptureResult.skipped("missing");
        }
        String hash = loaded.data() == null ? null : HistoryObjectStore.hashOf(loaded.data());
        if (hash != null && head != null && hash.equals(head.contentHash())) {
            // Back with its pre-deletion content (restored in the app or put back outside): not new, but not deleted.
            if (head.deletedAt() != null && deferredUnmarks != null) {
                deferredUnmarks.add(key);
            } else if (head.deletedAt() != null) {
                unmarkDeleted(key);
            }
            return CaptureResult.skipped("unchanged", hash);
        }
        String normHash = null;
        if (loaded.data() != null && isText(request.path(), loaded.data())) {
            normHash = HistoryObjectStore.hashOf(stripCarriageReturns(loaded.data()));
        }
        Long activityId = resolveActivity(source, now, activity);
        HistoryVersion prev = head == null ? null : version(head.headVersionId()).orElse(null);
        boolean fold = prev != null && !prev.sealed() && prev.source() == source
                && Objects.equals(prev.activityId(), activityId)
                && (source == HistorySource.APP || source == HistorySource.EXTERNAL)
                && now - prev.capturedAt() < policy.foldWindow().toMillis();
        String skippedReason = loaded.data() == null ? "too-large" : null;
        boolean sealedRow = source == HistorySource.BASELINE || source == HistorySource.SNAPSHOT
                || source == HistorySource.RESTORE;
        HistoryObjectStore.StoredObject stored = loaded.data() == null ? null : objects.store(loaded.data());
        String finalNormHash = normHash;
        long versionId = database.transaction(tx -> {
            if (stored != null) {
                tx.update("insert or ignore into lh_objects(hash, size, stored_size, created_at) values (?, ?, ?, ?)",
                        stored.hash(), stored.size(), stored.storedSize(), now);
            }
            long id;
            // Fold only while unsealed: if it was labeled or sealed since the check, insert a new version instead.
            int folded = fold ? tx.update("""
                    update lh_versions set object_hash = ?, norm_hash = ?, size = ?, captured_at = ?, path = ?,
                           skipped_reason = ? where version_id = ? and sealed = 0
                    """, hash, finalNormHash, loaded.size(), now, request.path(), skippedReason, prev.versionId()) : 0;
            if (folded == 1) {
                id = prev.versionId();
            } else {
                tx.update("""
                        insert into lh_versions(key_kind, key_id, path, object_hash, norm_hash, size, captured_at,
                               source, activity_id, label, sealed, skipped_reason)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, key.kind(), key.id(), request.path(), hash, finalNormHash, loaded.size(), now,
                        source.name(), activityId, request.label(), sealedRow ? 1 : 0, skippedReason);
                id = tx.queryOne("select last_insert_rowid() as id", row -> row.getLong("id")).orElseThrow();
            }
            tx.update("""
                    insert into lh_heads(key_kind, key_id, path, head_version_id, content_hash, deleted_at)
                    values (?, ?, ?, ?, ?, null)
                    on conflict(key_kind, key_id) do update set path = excluded.path,
                        head_version_id = excluded.head_version_id, content_hash = excluded.content_hash,
                        deleted_at = null
                    """, key.kind(), key.id(), request.path(), id, hash);
            if (activityId != null) {
                tx.update("update lh_activities set ended_at = ? where activity_id = ?", now, activityId);
            }
            return id;
        });
        if (source == HistorySource.EXTERNAL) {
            externalLastCaptureAt = now;
        }
        return hash == null ? CaptureResult.skipped("too-large") : CaptureResult.captured(hash, versionId);
    }

    /** Loaded content; {@code data} is null when the file exceeded the size limit and only its size is known. */
    private record Loaded(byte[] data, long size) {
    }

    private Loaded load(CaptureRequest request) {
        ContentSource content = request.content();
        if (content instanceof ContentSource.Bytes bytes) {
            byte[] data = bytes.data();
            return data.length > policy.maxObjectBytes() ? new Loaded(null, data.length) : new Loaded(data, data.length);
        }
        Path path = ((ContentSource.Disk) content).path();
        try {
            if (!Files.isRegularFile(path)) {
                return null;
            }
            BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class);
            if (before.size() > policy.maxObjectBytes()) {
                return new Loaded(null, before.size());
            }
            byte[] data = Files.readAllBytes(path);
            BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class);
            if (after.size() != before.size() || !after.lastModifiedTime().equals(before.lastModifiedTime())) {
                data = Files.readAllBytes(path);
            }
            return new Loaded(data, data.length);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read file to capture: " + path, e);
        }
    }

    private boolean isText(String path, byte[] data) {
        if (policy.isTextName(path)) {
            return true;
        }
        int limit = Math.min(SNIFF_BYTES, data.length);
        for (int i = 0; i < limit; i++) {
            if (data[i] == 0) {
                return false;
            }
        }
        return true;
    }

    static byte[] stripCarriageReturns(byte[] data) {
        byte[] out = new byte[data.length];
        int n = 0;
        for (byte b : data) {
            if (b != '\r') {
                out[n++] = b;
            }
        }
        return n == data.length ? data : java.util.Arrays.copyOf(out, n);
    }

    private synchronized Long resolveActivity(HistorySource source, long now, Activity activity) {
        if (source == HistorySource.APP || source == HistorySource.RESTORE) {
            return activity == null ? null : activity.id();
        }
        if (source != HistorySource.EXTERNAL) {
            return null;
        }
        if (externalActivityId != null && now - externalLastCaptureAt <= policy.externalIdleGap().toMillis()) {
            return externalActivityId;
        }
        externalActivityId = insertActivity(EXTERNAL_ACTIVITY_NAME, HistorySource.EXTERNAL, now, null);
        externalLastCaptureAt = now;
        return externalActivityId;
    }

    private long insertActivity(String name, HistorySource source, long now, String params) {
        return database.transaction(tx -> {
            tx.update("insert into lh_activities(name, source, started_at, ended_at, params) values (?, ?, ?, null, ?)",
                    name, source.name(), now, params);
            return tx.queryOne("select last_insert_rowid() as id", row -> row.getLong("id")).orElseThrow();
        });
    }

    @Override
    public void seal(HistoryKey key) {
        ordered(() -> database.write(session -> session.update("""
                update lh_versions set sealed = 1 where version_id =
                  (select head_version_id from lh_heads where key_kind = ? and key_id = ?)
                """, key.kind(), key.id())));
    }

    @Override
    public void sealAll() {
        database.write(session -> session.update(
                "update lh_versions set sealed = 1 where version_id in (select head_version_id from lh_heads)"));
    }

    @Override
    public void markDeleted(HistoryKey key, long deletedAt) {
        ordered(() -> markDeletedNow(marks(List.of(key)), deletedAt));
    }

    @Override
    public void markDeleted(Collection<HistoryKey> keys, long deletedAt) {
        List<Mark> batch = marks(keys);
        if (!batch.isEmpty()) {
            ordered(() -> markDeletedNow(batch, deletedAt));
        }
    }

    @Override
    public void markDeleted(Map<HistoryKey, String> pathsByKey, long deletedAt) {
        List<Mark> batch = marks(pathsByKey);
        if (!batch.isEmpty()) {
            ordered(() -> markDeletedNow(batch, deletedAt));
        }
    }

    @Override
    public void markDeletedLater(HistoryKey key, long deletedAt) {
        orderedLater(() -> markDeletedNow(marks(List.of(key)), deletedAt));
    }

    @Override
    public void markDeletedLater(Collection<HistoryKey> keys, long deletedAt) {
        List<Mark> batch = marks(keys);
        if (!batch.isEmpty()) {
            orderedLater(() -> markDeletedNow(batch, deletedAt));
        }
    }

    @Override
    public void markDeletedLater(Map<HistoryKey, String> pathsByKey, long deletedAt) {
        List<Mark> batch = marks(pathsByKey);
        if (!batch.isEmpty()) {
            orderedLater(() -> markDeletedNow(batch, deletedAt));
        }
    }

    /** One delete mark; a null {@code path} leaves the head's path unchanged. */
    private record Mark(HistoryKey key, String path) {
        Mark {
            Objects.requireNonNull(key, "key");
        }
    }

    private static List<Mark> marks(Collection<HistoryKey> keys) {
        return keys.stream().map(key -> new Mark(key, null)).toList();
    }

    private static List<Mark> marks(Map<HistoryKey, String> pathsByKey) {
        return pathsByKey.entrySet().stream()
                .map(entry -> new Mark(entry.getKey(), Objects.requireNonNull(entry.getValue(), "path")))
                .toList();
    }

    /** Marks the batch in a few transactions; don't go back to one per key, since each opens its own connection. */
    private void markDeletedNow(List<Mark> marks, long deletedAt) {
        for (int from = 0; from < marks.size(); from += KEY_CHUNK) {
            List<Mark> chunk = marks.subList(from, Math.min(from + KEY_CHUNK, marks.size()));
            database.transaction(tx -> {
                for (Mark mark : chunk) {
                    HistoryKey key = mark.key();
                    tx.update("""
                            update lh_versions set sealed = 1 where version_id =
                              (select head_version_id from lh_heads where key_kind = ? and key_id = ?)
                            """, key.kind(), key.id());
                    // Keep an existing mark's time and path; the right-hand sides read the values before the update.
                    tx.update("""
                            update lh_heads set path = case when deleted_at is null then coalesce(?, path) else path end,
                                   deleted_at = coalesce(deleted_at, ?)
                             where key_kind = ? and key_id = ?
                            """, mark.path(), deletedAt, key.kind(), key.id());
                }
                return null;
            });
        }
    }

    @Override
    public void unmarkDeleted(HistoryKey key) {
        ordered(() -> database.write(session -> session.update(
                "update lh_heads set deleted_at = null where key_kind = ? and key_id = ?", key.kind(), key.id())));
    }

    @Override
    public boolean hasHead(HistoryKey key) {
        return findHead(key).isPresent();
    }

    @Override
    public Optional<String> headPath(HistoryKey key) {
        return findHead(key).map(Head::path);
    }

    @Override
    public long beginActivity(String name, HistorySource source) {
        return beginActivity(name, source, null);
    }

    @Override
    public long beginActivity(String name, HistorySource source, String params) {
        Objects.requireNonNull(name, "name");
        long id = insertActivity(name, Objects.requireNonNull(source, "source"), clock.millis(), params);
        currentActivity.set(new Activity(id, source));
        return id;
    }

    @Override
    public void endActivity(long activityId) {
        database.write(session -> session.update(
                "update lh_activities set ended_at = coalesce(ended_at, ?) where activity_id = ?",
                clock.millis(), activityId));
        Activity current = currentActivity.get();
        if (current != null && current.id() == activityId) {
            currentActivity.compareAndSet(current, null);
        }
    }

    @Override
    public List<HistoryVersion> versions(HistoryKey key) {
        return database.read(session -> session.queryList(
                VERSION_SELECT + " where v.key_kind = ? and v.key_id = ? order by v.captured_at desc, v.version_id desc",
                SqliteLocalHistoryStore::mapVersion, key.kind(), key.id()));
    }

    @Override
    public Optional<HistoryVersion> version(long versionId) {
        return database.read(session -> session.queryOne(VERSION_SELECT + " where v.version_id = ?",
                SqliteLocalHistoryStore::mapVersion, versionId));
    }

    @Override
    public Optional<byte[]> content(long versionId) {
        return version(versionId).map(HistoryVersion::objectHash).flatMap(objects::read);
    }

    @Override
    public List<DeletedEntry> deletedEntries() {
        long retention = policy.deletedRetention().toMillis();
        return database.read(session -> session.queryList(
                "select " + VERSION_COLUMNS + ", h.path as head_path, h.deleted_at as deleted_at"
                        + " from lh_versions v left join lh_activities a on a.activity_id = v.activity_id"
                        + " join lh_heads h on h.key_kind = v.key_kind and h.key_id = v.key_id"
                        + " and h.head_version_id = v.version_id"
                        + " where h.deleted_at is not null order by h.deleted_at desc",
                row -> {
                    HistoryVersion last = mapVersion(row);
                    long deletedAt = row.getLong("deleted_at");
                    return new DeletedEntry(last.key(), row.getString("head_path"), deletedAt, deletedAt + retention,
                            last);
                }));
    }

    @Override
    public void label(long versionId, String label) {
        database.write(session -> session.update("update lh_versions set label = ?, sealed = 1 where version_id = ?",
                label == null || label.isBlank() ? null : label.trim(), versionId));
    }

    /** Runs after the queued captures; otherwise a pending capture would give the purged history a new head. */
    @Override
    public void purge(HistoryKey key) {
        ordered(() -> database.transaction(tx -> {
            tx.update("delete from lh_versions where key_kind = ? and key_id = ?", key.kind(), key.id());
            tx.update("delete from lh_heads where key_kind = ? and key_id = ?", key.kind(), key.id());
            return null;
        }));
    }

    /** Runs after the queued captures like {@link #purge}, so a pending capture can't revive the old key. */
    @Override
    public void rekey(HistoryKey from, HistoryKey to) {
        // Same key: only clear the delete mark; the first delete below would remove its own head.
        if (from.equals(to)) {
            unmarkDeleted(from);
            return;
        }
        ordered(() -> database.transaction(tx -> {
            tx.update("delete from lh_heads where key_kind = ? and key_id = ?", to.kind(), to.id());
            tx.update("update lh_versions set key_kind = ?, key_id = ? where key_kind = ? and key_id = ?",
                    to.kind(), to.id(), from.kind(), from.id());
            tx.update("update lh_heads set key_kind = ?, key_id = ?, deleted_at = null where key_kind = ? and key_id = ?",
                    to.kind(), to.id(), from.kind(), from.id());
            return null;
        }));
    }

    @Override
    public void purgeExpiredDeleted() {
        retention.purgeExpiredDeleted(clock.millis());
    }

    @Override
    public Set<String> objectHashes(String kind) {
        return database.read(session -> new HashSet<>(session.queryList(
                "select distinct object_hash from lh_versions where key_kind = ? and object_hash is not null",
                row -> row.getString("object_hash"), kind)));
    }

    @Override
    public Set<String> keysWithHead(String kind) {
        return database.read(session -> new HashSet<>(session.queryList(
                "select key_id from lh_heads where key_kind = ?", row -> row.getString("key_id"), kind)));
    }

    @Override
    public HistoryStats stats() {
        return database.read(session -> {
            long bytes = session.queryOne("select coalesce(sum(stored_size), 0) as b from lh_objects",
                    row -> row.getLong("b")).orElse(0L);
            long count = session.queryOne("select count(*) as c from lh_versions", row -> row.getLong("c")).orElse(0L);
            return new HistoryStats(bytes, count, null);
        });
    }

    /** Runs maintenance without holding the write lock throughout; {@link HistoryRetention} takes it for the sweep. */
    @Override
    public void maintain() {
        retention.run(clock.millis());
    }

    /** A row of {@code lh_heads}. */
    record Head(String path, long headVersionId, String contentHash, Long deletedAt) {
    }

    /** The explicit activity in progress. */
    private record Activity(long id, HistorySource source) {
    }

    Optional<Head> findHead(HistoryKey key) {
        return database.read(session -> session.queryOne(
                "select path, head_version_id, content_hash, deleted_at from lh_heads where key_kind = ? and key_id = ?",
                row -> new Head(row.getString("path"), row.getLong("head_version_id"), row.getString("content_hash"),
                        nullableLong(row, "deleted_at")),
                key.kind(), key.id()));
    }

    static HistoryVersion mapVersion(DatabaseRow row) {
        return new HistoryVersion(
                row.getLong("version_id"),
                new HistoryKey(row.getString("key_kind"), row.getString("key_id")),
                row.getString("path"),
                row.getString("object_hash"),
                row.getString("norm_hash"),
                row.getLong("size"),
                row.getLong("captured_at"),
                HistorySource.valueOf(row.getString("source")),
                nullableLong(row, "activity_id"),
                row.getString("activity_name"),
                row.getString("activity_params"),
                row.getString("label"),
                row.getLong("sealed") != 0,
                row.getString("skipped_reason"));
    }

    static Long nullableLong(DatabaseRow row, String column) {
        String raw = row.getString(column);
        return raw == null ? null : Long.valueOf(raw);
    }
}
