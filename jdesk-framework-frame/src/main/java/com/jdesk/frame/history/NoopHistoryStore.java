package com.jdesk.frame.history;

import com.jdesk.core.history.CaptureRequest;
import com.jdesk.core.history.CaptureResult;
import com.jdesk.core.history.DeletedEntry;
import com.jdesk.core.history.HistoryKey;
import com.jdesk.core.history.HistoryPolicy;
import com.jdesk.core.history.HistorySource;
import com.jdesk.core.history.HistoryStats;
import com.jdesk.core.history.HistoryUnavailableReason;
import com.jdesk.core.history.HistoryVersion;
import com.jdesk.core.history.LocalHistoryStore;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Empty store used when the history database can't be opened: keeps nothing, and {@link #stats()} reports why. */
final class NoopHistoryStore implements LocalHistoryStore {

    private final String scopeId;
    private final HistoryPolicy policy;
    private final HistoryUnavailableReason reason;

    NoopHistoryStore(String scopeId, HistoryPolicy policy, HistoryUnavailableReason reason) {
        this.scopeId = Objects.requireNonNull(scopeId, "scopeId");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.reason = Objects.requireNonNull(reason, "reason");
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
        return CompletableFuture.completedFuture(CaptureResult.skipped("unavailable"));
    }

    @Override
    public CompletableFuture<CaptureResult> captureBaseline(CaptureRequest request) {
        return CompletableFuture.completedFuture(CaptureResult.skipped("unavailable"));
    }

    @Override
    public void seal(HistoryKey key) {
    }

    @Override
    public void sealAll() {
    }

    @Override
    public void markDeleted(HistoryKey key, long deletedAt) {
    }

    @Override
    public void markDeleted(Map<HistoryKey, String> pathsByKey, long deletedAt) {
    }

    @Override
    public void markDeletedLater(Map<HistoryKey, String> pathsByKey, long deletedAt) {
    }

    @Override
    public void unmarkDeleted(HistoryKey key) {
    }

    @Override
    public boolean hasHead(HistoryKey key) {
        return false;
    }

    @Override
    public long beginActivity(String name, HistorySource source) {
        return -1;
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
        return Set.of();
    }

    @Override
    public HistoryStats stats() {
        return HistoryStats.unavailable(reason);
    }

    @Override
    public void maintain() {
    }
}
