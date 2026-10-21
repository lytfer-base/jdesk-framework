package com.jdesk.core.history;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Local history of one scope: an object store plus a version log. Capture methods run asynchronously in submission
 * order, so each key keeps its order; queries read synchronously on the calling thread, so don't call them on the
 * EDT. History must never hold up writes: a failed capture is only logged and returns {@code captured=false}.
 */
public interface LocalHistoryStore {

    String scopeId();

    HistoryPolicy policy();

    /**
     * Captures a version: unchanged content reports unchanged, the same source and activity within the fold window
     * rewrite the head version, and anything else adds a row.
     */
    CompletableFuture<CaptureResult> capture(CaptureRequest request);

    /**
     * Like {@link #capture} for a batch, only queued without waiting for the writes. Files from one reconciliation
     * must go through here, not one capture each; implementations that can batch writes should override the loop.
     */
    default void captureAll(Collection<CaptureRequest> requests) {
        for (CaptureRequest request : requests) {
            capture(request);
        }
    }

    /** Records a {@link HistorySource#BASELINE} version unless the key already has a head version. */
    CompletableFuture<CaptureResult> captureBaseline(CaptureRequest request);

    /** Seals the head version, so later writes start a new row. */
    void seal(HistoryKey key);

    /** Seals every head version; call it before closing the store. */
    void sealAll();

    /**
     * Marks the key deleted and starts its retention clock, keeping the time of an earlier mark; the mark is written
     * when the call returns.
     */
    void markDeleted(HistoryKey key, long deletedAt);

    /**
     * Like {@link #markDeleted(HistoryKey, long)} for a batch; deleting a whole folder must go through here, not a
     * loop. Implementations that can batch writes should override the default loop.
     */
    default void markDeleted(Collection<HistoryKey> keys, long deletedAt) {
        for (HistoryKey key : keys) {
            markDeleted(key, deletedAt);
        }
    }

    /**
     * Like {@link #markDeleted(HistoryKey, long)} but only queued, so a large deletion doesn't hold up the shared VFS
     * reconciliation thread. Implementations with a queue must override the default, which writes right away.
     */
    default void markDeletedLater(HistoryKey key, long deletedAt) {
        markDeleted(key, deletedAt);
    }

    /** Like {@link #markDeletedLater(HistoryKey, long)} for a batch; deleting a whole folder must go through here. */
    default void markDeletedLater(Collection<HistoryKey> keys, long deletedAt) {
        for (HistoryKey key : keys) {
            markDeletedLater(key, deletedAt);
        }
    }

    /**
     * Like {@link #markDeleted(Collection, long)}, also setting each head row's path to where the file was when it was
     * deleted; an earlier mark keeps its time and path. Implementations that store head paths must override this.
     */
    default void markDeleted(Map<HistoryKey, String> pathsByKey, long deletedAt) {
        markDeleted(pathsByKey.keySet(), deletedAt);
    }

    /** Like {@link #markDeleted(Map, long)} but only queued, as with {@link #markDeletedLater(HistoryKey, long)}. */
    default void markDeletedLater(Map<HistoryKey, String> pathsByKey, long deletedAt) {
        markDeletedLater(pathsByKey.keySet(), deletedAt);
    }

    /**
     * Waits for the submitted captures and queued writes and returns {@code false} if some are still pending at the
     * timeout. Must be called before closing the store, or queued deletion marks hit a closed handle and are lost.
     */
    default boolean awaitPending(Duration timeout) {
        return true;
    }

    /**
     * Returns a future that completes, without blocking, once everything queued so far has run; wait for it before
     * the UI reads a change that just happened.
     */
    default CompletableFuture<Void> afterQueued() {
        return CompletableFuture.completedFuture(null);
    }

    void unmarkDeleted(HistoryKey key);

    boolean hasHead(HistoryKey key);

    /**
     * Returns the path in the head row, written by the last capture that stored a version or by a deletion mark with
     * a path; empty without a head row. Reliable only for deleted entries; don't use it to find an existing file.
     */
    default Optional<String> headPath(HistoryKey key) {
        return Optional.empty();
    }

    /** Begins an explicit activity that APP captures belong to until {@link #endActivity}. */
    long beginActivity(String name, HistorySource source);

    /**
     * Like {@link #beginActivity(String, HistorySource)}, also storing caller-defined parameter text such as JSON that
     * {@link HistoryVersion#activityParams()} reads back; {@code params} may be null. The default drops it.
     */
    default long beginActivity(String name, HistorySource source, String params) {
        return beginActivity(name, source);
    }

    void endActivity(long activityId);

    /** Returns all versions of the key, newest first. */
    List<HistoryVersion> versions(HistoryKey key);

    Optional<HistoryVersion> version(long versionId);

    /** Returns the content of a version; empty if none was stored or its object file is missing. */
    Optional<byte[]> content(long versionId);

    List<DeletedEntry> deletedEntries();

    void label(long versionId, String label);

    /** Deletes every version and the head row of the key; the objects are left to the sweep. */
    void purge(HistoryKey key);

    /** Purges the expired deleted entries, labeled versions included. */
    void purgeExpiredDeleted();

    /**
     * Moves every version and the head row from {@code from} to {@code to} and clears the deletion mark, for a deleted
     * file restored under a new ID; with equal keys it only clears the mark.
     */
    void rekey(HistoryKey from, HistoryKey to);

    /** Returns the object hashes referenced by all versions of a kind. */
    Set<String> objectHashes(String kind);

    /**
     * Returns every ID of a kind that has a head version, deleted ones included; baseline checks should fetch them all
     * here instead of calling hasHead per key.
     */
    Set<String> keysWithHead(String kind);

    HistoryStats stats();

    /** Thins versions, purges expired ones, enforces the size limit and sweeps unused objects. */
    void maintain();
}
