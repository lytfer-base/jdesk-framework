package com.jdesk.frame.vfs;

import com.jdesk.core.io.Cancellation;
import com.jdesk.core.io.FileLinks;
import com.jdesk.core.io.InterruptibleFiles;
import com.jdesk.core.vfs.ScanFilter;
import com.jdesk.core.vfs.VfsWatchListener;
import com.jdesk.core.vfs.VfsWatcher;
import io.methvin.watcher.DirectoryChangeEvent;
import io.methvin.watcher.DirectoryChangeListener;
import io.methvin.watcher.DirectoryWatcher;
import io.methvin.watcher.visitor.FileTreeVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Global {@link VfsWatcher} on {@code io.methvin:directory-watcher}: one instance watches every root on a fixed set
 * of threads, like IntelliJ's application-wide FileWatcher. {@link #setRoots} may run on the EDT, so it only stores
 * the target and rebuilds in the background: watching hasn't started when it returns, and changes made during a
 * rebuild are missed.
 */
public final class DirectoryWatcherVfsWatcher implements VfsWatcher {

    private static final Logger LOG = LoggerFactory.getLogger(DirectoryWatcherVfsWatcher.class);

    private static final long DEBOUNCE_MILLIS = 200;
    /** Longest debounce wait: continuous changes, such as a download, must still flush, or every root waits. */
    private static final long MAX_DEBOUNCE_MILLIS = 2000;
    /** Rebuild backoff after the watcher fails or terminates unexpectedly. */
    private static final long RETRY_MIN_MILLIS = 2000;
    private static final long RETRY_MAX_MILLIS = 60_000;

    private final ScanFilter filter;
    private final VfsWatchListener listener;
    /**
     * Runs the blocking {@code watchAsync} event loop, which holds its thread until close.
     * Don't merge it with {@link #scheduler}: debounce tasks would never get the thread.
     */
    private final ExecutorService watchExecutor;
    /** Runs debounce flushes and rebuilds, keeping the rebuild's tree walk off the caller's thread. */
    private final ScheduledExecutorService scheduler;
    private final Set<Path> pending = new HashSet<>();
    private ScheduledFuture<?> debounce;
    /** When the first change of the pending batch arrived, for the {@link #MAX_DEBOUNCE_MILLIS} cap. */
    private long batchStartedAt;
    /** Replaced on each rebuild; null without roots or after a failed rebuild. */
    private DirectoryWatcher watcher;
    /** Roots requested by the latest {@link #setRoots}. */
    private Set<Path> targetRoots = Set.of();
    /**
     * Roots actually watched, without broken ones. Kept apart from {@link #targetRoots} so that a repeated
     * {@link #setRoots} still retries once a broken root comes back.
     */
    private Set<Path> watchedRoots = Set.of();
    private ScheduledFuture<?> rebuildTask;
    private long rebuildDueAt;
    /** Last rebuild backoff; 0 after a successful rebuild. */
    private long retryDelay;
    private boolean overflowScheduled;
    private boolean disposed;

    public DirectoryWatcherVfsWatcher(ScanFilter filter, VfsWatchListener listener) {
        this.filter = Objects.requireNonNull(filter, "filter");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.watchExecutor = Executors.newSingleThreadExecutor(daemonThreadFactory("vfs-watch"));
        this.scheduler = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory("vfs-debounce"));
    }

    /** Stores the target roots and rebuilds asynchronously; does nothing if they are unchanged and all watched. */
    @Override
    public synchronized void setRoots(Set<Path> newRoots) {
        if (disposed) {
            throw new IllegalStateException("Watcher is disposed");
        }
        Set<Path> normalized = Objects.requireNonNull(newRoots, "roots").stream()
                .map(path -> path.toAbsolutePath().normalize())
                .collect(Collectors.toUnmodifiableSet());
        if (normalized.equals(targetRoots) && watchedRoots.equals(normalized)) {
            return;
        }
        targetRoots = normalized;
        retryDelay = 0; // A caller's request beats the backoff.
        scheduleRebuild(0);
    }

    /**
     * Schedules a rebuild unless one is already due no later, since it reads the latest {@link #targetRoots}; an
     * earlier request replaces a queued backoff. Caller must hold the lock.
     */
    private void scheduleRebuild(long delayMillis) {
        long dueAt = System.currentTimeMillis() + delayMillis;
        if (rebuildTask != null && dueAt >= rebuildDueAt) {
            return;
        }
        if (rebuildTask != null) {
            rebuildTask.cancel(false);
        }
        try {
            rebuildTask = scheduler.schedule(this::rebuild, delayMillis, TimeUnit.MILLISECONDS);
            rebuildDueAt = dueAt;
        } catch (RejectedExecutionException e) {
            rebuildTask = null; // Shut down by a racing dispose: nothing to rebuild.
        }
    }

    /** Schedules a retry with doubling backoff after a failed build or unexpected end; caller must hold the lock. */
    private void scheduleRetry() {
        retryDelay = retryDelay == 0 ? RETRY_MIN_MILLIS : Math.min(retryDelay * 2, RETRY_MAX_MILLIS);
        scheduleRebuild(retryDelay);
    }

    /**
     * Rebuilds the watcher on the {@link #scheduler} thread, doing the slow close, build and tree walk outside the
     * lock. A cancelled walk ends like a close: nothing installed, no retry, no WARN.
     */
    private void rebuild() {
        Set<Path> target;
        DirectoryWatcher previous;
        synchronized (this) {
            rebuildTask = null;
            if (disposed) {
                return;
            }
            target = targetRoots;
            previous = watcher;
            watcher = null;
        }
        closeQuietly(previous);

        Set<Path> usable = usableRoots(target);
        if (usable.isEmpty()) {
            synchronized (this) {
                watchedRoots = Set.of(); // No usable roots: no watcher; the threads stay for reuse.
                if (!target.isEmpty() && !disposed) {
                    scheduleRetry(); // Roots gone (drive removed, folder renamed): resume once they are back.
                }
            }
            return;
        }

        DirectoryWatcher built;
        try {
            built = DirectoryWatcher.builder()
                    .paths(List.copyOf(usable))
                    .fileHashing(false)
                    .fileTreeVisitor(new LinkSkippingTreeVisitor(usable))
                    .listener(libraryListener())
                    .build();
        } catch (IOException | RuntimeException e) {
            LOG.warn("vfs.watcher.buildFailed roots={} (unwatched until a later retry)", usable, e);
            synchronized (this) {
                watchedRoots = Set.of();
                if (!disposed) {
                    scheduleRetry();
                }
            }
            return;
        }

        // registerPaths() runs here and the library turns failures into a failed future; must check it.
        CompletableFuture<Void> loop = built.watchAsync(watchExecutor);
        loop.whenComplete((ignored, error) -> onEventLoopEnded(built, usable, error));
        boolean cancelled = Cancellation.isCancellation(loop.handle((ignored, error) -> error).getNow(null));
        boolean install;
        synchronized (this) {
            // Must check isDone under the lock: the loop may end right now, and a dead watcher never recovers.
            install = !disposed && !loop.isDone();
            if (install) {
                watcher = built;
                watchedRoots = usable;
                retryDelay = 0;
                if (!targetRoots.equals(target)) {
                    scheduleRebuild(0); // The target changed meanwhile.
                }
            } else {
                watchedRoots = Set.of(); // Not installed: a repeated setRoots must still rebuild.
                if (!disposed && !cancelled) {
                    scheduleRetry();
                }
            }
        }
        if (!install) {
            closeQuietly(built);
        }
        if (cancelled) {
            LOG.debug("vfs.watcher.rebuildCancelled roots={}", usable);
        }
    }

    /**
     * Listener for the library; any failure in event handling counts as an overflow, because the library throws on
     * its own OVERFLOW events before they reach us. Don't reconcile on the event loop thread: it worsens overflow.
     */
    private DirectoryChangeListener libraryListener() {
        return new DirectoryChangeListener() {
            @Override
            public void onEvent(DirectoryChangeEvent event) {
                DirectoryWatcherVfsWatcher.this.onEvent(event);
            }

            @Override
            public void onException(Exception failure) {
                scheduleOverflow(failure);
            }
        };
    }

    /** Schedules one debounced full rescan on the scheduler thread, unless one is already queued. */
    private void scheduleOverflow(Exception failure) {
        Set<Path> roots;
        synchronized (this) {
            if (disposed || overflowScheduled || watchedRoots.isEmpty()) {
                return;
            }
            roots = watchedRoots;
            overflowScheduled = true;
            try {
                scheduler.schedule(() -> runOverflow(roots), DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException rejected) {
                overflowScheduled = false; // Shut down by a racing dispose.
                return;
            }
        }
        if (failure == null || isLibraryOverflow(failure)) {
            LOG.warn("vfs.watcher.overflow roots={} (falling back to a full rescan)", roots);
        } else {
            LOG.warn("vfs.watcher.eventFailed roots={} (handled as overflow: full rescan)", roots, failure);
        }
    }

    /** Recognizes the NPE the library throws on its own OVERFLOW: the first non-JDK frame is its {@code onEvent}. */
    static boolean isLibraryOverflow(Throwable failure) {
        if (!(failure instanceof NullPointerException)) {
            return false;
        }
        for (StackTraceElement frame : failure.getStackTrace()) {
            String owner = frame.getClassName();
            if (!owner.startsWith("java.") && !owner.startsWith("jdk.")) {
                return owner.equals(DirectoryWatcher.class.getName()) && frame.getMethodName().equals("onEvent");
            }
        }
        return false;
    }

    private void runOverflow(Set<Path> roots) {
        synchronized (this) {
            overflowScheduled = false;
            if (disposed) {
                return;
            }
        }
        try {
            listener.overflow(roots);
        } catch (Throwable failure) {
            LOG.warn("vfs.watcher.rescanFailed roots={}", roots, failure);
        }
    }

    /**
     * Handles the end of an event loop; if it is still the current watcher, the end was unexpected and is retried.
     * Don't judge by {@code error}: the library also ends normally when all watch keys die, as when a root moves.
     */
    private void onEventLoopEnded(DirectoryWatcher source, Set<Path> attempted, Throwable error) {
        synchronized (this) {
            if (watcher != source) {
                return; // Closed on purpose, or replaced by a later rebuild.
            }
            watcher = null;
            watchedRoots = Set.of(); // So a repeated setRoots still rebuilds.
            if (disposed) {
                return;
            }
            scheduleRetry();
        }
        LOG.warn("vfs.watcher.terminated roots={} (a root was moved or the event loop failed; rebuilding later)",
                attempted, error);
    }

    /**
     * Drops roots that are no longer directories, since one would make {@code registerPaths} fail for every root.
     * A root can still vanish after the check; the {@code watchAsync} future catches that.
     */
    private Set<Path> usableRoots(Set<Path> target) {
        Set<Path> usable = new LinkedHashSet<>();
        List<Path> dropped = new ArrayList<>();
        for (Path root : target) {
            if (Files.isDirectory(root)) {
                usable.add(root);
            } else {
                dropped.add(root);
            }
        }
        if (!dropped.isEmpty()) {
            LOG.warn("vfs.watcher.rootsSkipped roots={} (not directories, maybe moved or renamed; others still watched)",
                    dropped);
        }
        return usable;
    }

    private void closeQuietly(DirectoryWatcher target) {
        if (target == null) {
            return;
        }
        try {
            target.close();
        } catch (IOException | RuntimeException e) {
            LOG.debug("vfs.watcher.closeFailed (continuing to rebuild or release)", e);
        }
    }

    private void onEvent(DirectoryChangeEvent event) {
        if (event.eventType() == DirectoryChangeEvent.EventType.OVERFLOW) {
            scheduleOverflow(null); // Unreachable with the current library (see libraryListener).
            return;
        }
        Path changed = event.path();
        // The caller's filter dispatches to the ScanFilter registered for the event's root.
        if (!filter.include(changed, event.isDirectory())) {
            return;
        }
        Path affected = event.isDirectory() ? changed : changed.getParent();
        if (affected == null) {
            return;
        }
        enqueue(affected);
    }

    private synchronized void enqueue(Path affectedDir) {
        if (disposed) {
            return;
        }
        long now = System.currentTimeMillis();
        if (pending.isEmpty()) {
            batchStartedAt = now;
        }
        pending.add(affectedDir);
        // Cap the trailing debounce: nonstop changes such as a download would otherwise never flush.
        long delay = Math.max(0, Math.min(DEBOUNCE_MILLIS, batchStartedAt + MAX_DEBOUNCE_MILLIS - now));
        if (debounce != null) {
            debounce.cancel(false);
        }
        try {
            debounce = scheduler.schedule(this::flush, delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // Shut down by a racing dispose: drop this batch.
        }
    }

    private void flush() {
        Set<Path> batch;
        synchronized (this) {
            if (pending.isEmpty()) {
                return;
            }
            batch = new HashSet<>(pending);
            pending.clear();
        }
        try {
            listener.changed(batch);
        } catch (Throwable failure) {
            // Must catch here: nobody reads this one-shot future, so the batch would vanish silently.
            LOG.warn("vfs.watcher.publishFailed dirs={} (reconcile failed; this batch was not published)",
                    batch, failure);
        }
    }

    @Override
    public void dispose() {
        DirectoryWatcher current;
        synchronized (this) {
            if (disposed) {
                return;
            }
            disposed = true;
            current = watcher;
            watcher = null;
            targetRoots = Set.of();
            watchedRoots = Set.of();
        }
        closeQuietly(current);
        watchExecutor.shutdownNow();
        scheduler.shutdownNow();
    }

    /** For tests: the roots actually watched; poll it after setRoots instead of sleeping, since rebuilds are async. */
    synchronized Set<Path> watchedRootsSnapshot() {
        return watchedRoots;
    }

    private static ThreadFactory daemonThreadFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * Tree walk for the watch library, run per root at start and per new directory on Windows: skips links
     * ({@link FileLinks}) other than a watched root itself, and throws InterruptedIOException once interrupted.
     */
    static final class LinkSkippingTreeVisitor implements FileTreeVisitor {

        private final Set<Path> roots;

        LinkSkippingTreeVisitor(Set<Path> roots) {
            this.roots = Set.copyOf(roots);
        }

        @Override
        public void recursiveVisitFiles(Path start, Callback onDirectory, Callback onFile) throws IOException {
            InterruptibleFiles.walkFileTree(start, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    boolean watchedRoot = dir.equals(start) && roots.contains(dir);
                    if (!watchedRoot && FileLinks.isLink(dir, attrs)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    onDirectory.call(dir);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!FileLinks.isLink(file, attrs)) {
                        onFile.call(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                // Keep both overrides: SimpleFileVisitor's defaults throw and abort the walk on an unreadable entry.
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }
}
