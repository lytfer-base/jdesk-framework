package com.jdesk.frame.vfs;

import com.jdesk.core.history.CaptureRequest;
import com.jdesk.core.history.ContentSource;
import com.jdesk.core.history.HistoryKey;
import com.jdesk.core.history.HistoryPolicy;
import com.jdesk.core.history.HistorySource;
import com.jdesk.core.history.LocalHistoryStore;
import com.jdesk.core.vfs.VfsCatalog;
import com.jdesk.core.vfs.VfsFileId;
import com.jdesk.core.vfs.VfsFileStatus;
import com.jdesk.core.vfs.VfsHistoryKeys;
import com.jdesk.core.vfs.VfsManagedFile;
import com.jdesk.core.vfs.VfsRootId;
import com.jdesk.core.vfs.VfsScanResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Feeds VFS changes into local history: captures added and updated files, marks missing ones deleted, and on a full
 * reconcile gives small text files without history a baseline. Captures are asynchronous; callers don't wait.
 * Decide here, not at call sites, what enters history.
 */
final class HistoryFeed {

    private HistoryFeed() {
    }

    /** Feeds one reconcile result; with {@code baselinePass}, rows without history go to {@link #baselineTree}. */
    static void feed(LocalHistoryStore store, VfsScanResult result, HistorySource source, boolean baselinePass) {
        long now = System.currentTimeMillis();
        // Fetch all heads at once: asking per file opens a connection each time.
        Set<String> heads = baselinePass ? store.keysWithHead(VfsHistoryKeys.FILE_KIND) : Set.of();
        List<CaptureRequest> captures = new ArrayList<>();
        for (VfsManagedFile file : result.added()) {
            offer(captures, file, source, baselinePass, heads);
        }
        for (VfsManagedFile file : result.updated()) {
            offer(captures, file, source, baselinePass, heads);
        }
        // Submit in bulk and don't wait: this thread is shared, and each separate call opens a connection.
        if (!captures.isEmpty()) {
            store.captureAll(captures);
        }
        // A rename or move lists one file id in both added and missing; the file lives, so don't mark it deleted.
        Set<VfsFileId> alive = new HashSet<>();
        for (VfsManagedFile file : result.added()) {
            alive.add(file.fileId());
        }
        Map<HistoryKey, String> deleted = new LinkedHashMap<>();
        for (VfsManagedFile file : result.missing()) {
            if (!file.directory() && !alive.contains(file.fileId())) {
                deleted.put(VfsHistoryKeys.of(file.fileId()), file.relativePath());
            }
        }
        if (!deleted.isEmpty()) {
            store.markDeletedLater(deleted, now);
        }
    }

    private static void offer(List<CaptureRequest> captures, VfsManagedFile file, HistorySource source,
                              boolean baselinePass, Set<String> heads) {
        if (!isFile(file)) {
            return;
        }
        HistoryKey key = VfsHistoryKeys.of(file.fileId());
        if (baselinePass && !heads.contains(key.id())) {
            return;
        }
        captures.add(request(key, file, source));
    }

    /** Captures a file its writer just saved. */
    static void capture(LocalHistoryStore store, VfsManagedFile file, HistorySource source) {
        if (isFile(file)) {
            store.capture(request(VfsHistoryKeys.of(file.fileId()), file, source));
        }
    }

    /** Gives a text file opened in the app a baseline if it has no history yet, whatever its size. */
    static void baselineOnOpen(LocalHistoryStore store, VfsManagedFile file) {
        if (!isFile(file) || !store.policy().isTextName(file.relativePath())) {
            return;
        }
        HistoryKey key = VfsHistoryKeys.of(file.fileId());
        if (!store.hasHead(key)) {
            store.captureBaseline(request(key, file, HistorySource.BASELINE));
        }
    }

    /** Gives each text file in the snapshot without history and within the eager baseline limit a baseline. */
    static void baselineTree(LocalHistoryStore store, VfsCatalog catalog, VfsRootId rootId) {
        HistoryPolicy policy = store.policy();
        Set<String> heads = store.keysWithHead(VfsHistoryKeys.FILE_KIND);
        for (VfsManagedFile entry : catalog.listAll(rootId)) {
            if (entry.directory() || entry.status() != VfsFileStatus.PRESENT) {
                continue;
            }
            if (heads.contains(entry.fileId().value()) || entry.size() > policy.eagerBaselineMaxBytes()
                    || !policy.isTextName(entry.relativePath())) {
                continue;
            }
            store.captureBaseline(request(VfsHistoryKeys.of(entry.fileId()), entry, HistorySource.BASELINE));
        }
    }

    private static boolean isFile(VfsManagedFile file) {
        return file != null && !file.directory() && file.status() == VfsFileStatus.PRESENT;
    }

    private static CaptureRequest request(HistoryKey key, VfsManagedFile file, HistorySource source) {
        return CaptureRequest.of(key, file.relativePath(), ContentSource.disk(file.absolutePath()), source);
    }
}
