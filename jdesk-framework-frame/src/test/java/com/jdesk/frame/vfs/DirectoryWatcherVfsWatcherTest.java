package com.jdesk.frame.vfs;

import com.jdesk.core.disposer.Disposer;
import com.jdesk.core.vfs.ScanFilter;
import com.jdesk.core.vfs.VfsWatchListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DirectoryWatcherVfsWatcherTest {

    /** Collects the absolute paths from changed and overflow callbacks. */
    private static final class CollectingListener implements VfsWatchListener {
        final Set<Path> changed = ConcurrentHashMap.newKeySet();
        final Set<Path> overflowed = ConcurrentHashMap.newKeySet();
        @Override public void changed(Set<Path> affectedDirs) { changed.addAll(affectedDirs); }
        @Override public void overflow(Set<Path> roots) { overflowed.addAll(roots); }
    }

    /** Polls until the value arrives; WatchService has OS latency plus the debounce, so a fixed sleep won't do. */
    private static void awaitContains(Set<Path> set, Path value, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (set.contains(value)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("timed out waiting for affected directory: " + value + ", got: " + set);
    }

    /**
     * Polls until the asynchronous rebuild is live. setRoots returns before watching starts, so a test must wait
     * here before writing files, or the events are missed.
     */
    private static void awaitWatched(DirectoryWatcherVfsWatcher watcher, Set<Path> expected, long timeoutMillis)
            throws InterruptedException {
        Set<Path> want = expected.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .collect(Collectors.toUnmodifiableSet());
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (watcher.watchedRootsSnapshot().equals(want)) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("watch not ready, expected: " + want + ", got: " + watcher.watchedRootsSnapshot());
    }

    /** On Windows the library walks a new directory to emit create events; it must not walk into a new junction. */
    @Test
    void newJunctionIsNotWalkedIntoForCreateEvents(@TempDir Path base) throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"), "junctions need NTFS");
        Path root = base.resolve("root");
        Files.createDirectories(root.resolve("sync"));
        Path outside = base.resolve("outside");
        Files.createDirectories(outside.resolve("sub"));
        Files.writeString(outside.resolve("sub").resolve("b.md"), "b");
        Path staged = base.resolve("staged");
        Path link = root.resolve("link");
        CollectingListener listener = new CollectingListener();
        DirectoryWatcherVfsWatcher watcher = new DirectoryWatcherVfsWatcher((p, d) -> true, listener);
        try {
            watcher.setRoots(Set.of(root));
            awaitWatched(watcher, Set.of(root), 8000);
            // mklink makes an empty directory and then the reparse point, so build it outside and move it in.
            Process mklink = new ProcessBuilder("cmd", "/c", "mklink", "/J", staged.toString(), outside.toString())
                    .redirectErrorStream(true).start();
            assumeTrue(mklink.waitFor() == 0 && Files.isDirectory(staged), "mklink /J failed");
            Files.move(staged, link);

            // Queued after the junction on the same event thread, so once it arrives the junction was handled.
            Files.writeString(root.resolve("sync").resolve("after.md"), "x");
            awaitContains(listener.changed, root.resolve("sync"), 8000);

            assertTrue(listener.changed.stream().noneMatch(path -> path.startsWith(link)),
                    "no events from inside the junction: " + listener.changed);
        } finally {
            Disposer.dispose(watcher);
            Files.deleteIfExists(link); // Delete the junction first so @TempDir cleanup doesn't follow it.
            Files.deleteIfExists(staged);
        }
    }

    /** The link-skipping walk enters a junction root but no junction below it, including one pointing back. */
    @Test
    void treeVisitorWalksAJunctionRootButSkipsLinksBelowIt(@TempDir Path base) throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"), "junctions need NTFS");
        Path realRoot = base.resolve("real");
        Files.createDirectories(realRoot.resolve("sub"));
        Files.writeString(realRoot.resolve("sub").resolve("a.md"), "a");
        Path outside = base.resolve("outside");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("c.md"), "c");
        List<Path> links = new ArrayList<>();
        try {
            Path root = junction(base.resolve("root"), realRoot, links);
            junction(realRoot.resolve("loop"), realRoot, links);
            junction(realRoot.resolve("shared"), outside, links);
            List<Path> dirs = new ArrayList<>();
            List<Path> files = new ArrayList<>();

            new DirectoryWatcherVfsWatcher.LinkSkippingTreeVisitor(Set.of(root))
                    .recursiveVisitFiles(root, dirs::add, files::add);

            assertEquals(Set.of(root, root.resolve("sub")), Set.copyOf(dirs));
            assertEquals(List.of(root.resolve("sub").resolve("a.md")), files);
        } finally {
            for (Path link : links) {
                Files.deleteIfExists(link); // Delete the junction first so @TempDir cleanup doesn't follow it.
            }
        }
    }

    /** The rebuild's tree walk is cancellable: an interrupt stops it before the next entry and keeps the flag. */
    @Test
    void treeVisitorStopsWhenItsThreadIsInterrupted(@TempDir Path base) throws Exception {
        Path root = Files.createDirectories(base.resolve("root"));
        Files.createDirectories(root.resolve("a"));
        Files.createDirectories(root.resolve("b"));
        List<Path> dirs = new ArrayList<>();
        try {
            assertThrows(InterruptedIOException.class, () ->
                    new DirectoryWatcherVfsWatcher.LinkSkippingTreeVisitor(Set.of(root)).recursiveVisitFiles(root,
                            dir -> {
                                dirs.add(dir);
                                Thread.currentThread().interrupt();
                            },
                            file -> { }));
            assertEquals(List.of(root), dirs, "the walk stops after the interrupt");
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag stays set");
        } finally {
            Thread.interrupted();
        }
    }

    private static Path junction(Path link, Path target, List<Path> created) throws Exception {
        Process mklink = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start();
        boolean ok = mklink.waitFor() == 0 && Files.isDirectory(link);
        if (ok) {
            created.add(link);
        }
        assumeTrue(ok, "mklink /J failed");
        return link;
    }

    @Test
    void watchesMultipleRootsWithOneInstance(@TempDir Path base) throws Exception {
        // One watcher instance watches both roots.
        Path rootA = Files.createDirectories(base.resolve("projA"));
        Path rootB = Files.createDirectories(base.resolve("projB"));
        CollectingListener listener = new CollectingListener();
        DirectoryWatcherVfsWatcher watcher = new DirectoryWatcherVfsWatcher((p, d) -> true, listener);
        try {
            watcher.setRoots(Set.of(rootA, rootB));
            awaitWatched(watcher, Set.of(rootA, rootB), 8000);
            Files.writeString(rootA.resolve("a.md"), "x");
            Files.writeString(rootB.resolve("b.md"), "y");
            awaitContains(listener.changed, rootA, 8000);
            awaitContains(listener.changed, rootB, 8000);
        } finally {
            Disposer.dispose(watcher);
        }
    }

    @Test
    void setRootsReplacesWatchedSet(@TempDir Path base) throws Exception {
        // setRoots replaces the whole set: after switching to B, A's changes aren't reported.
        Path rootA = Files.createDirectories(base.resolve("projA"));
        Path rootB = Files.createDirectories(base.resolve("projB"));
        CollectingListener listener = new CollectingListener();
        DirectoryWatcherVfsWatcher watcher = new DirectoryWatcherVfsWatcher((p, d) -> true, listener);
        try {
            watcher.setRoots(Set.of(rootA));
            awaitWatched(watcher, Set.of(rootA), 8000);
            Files.writeString(rootA.resolve("a.md"), "x");
            awaitContains(listener.changed, rootA, 8000);

            watcher.setRoots(Set.of(rootB));      // A is no longer watched.
            awaitWatched(watcher, Set.of(rootB), 8000);
            listener.changed.clear();
            Files.writeString(rootA.resolve("a2.md"), "x");
            Files.writeString(rootB.resolve("b.md"), "y");
            awaitContains(listener.changed, rootB, 8000);
            assertFalse(listener.changed.contains(rootA), "the old root should not be reported after setRoots");
        } finally {
            Disposer.dispose(watcher);
        }
    }

    /**
     * A missing root (folder moved or renamed) must not stop the others being watched: a broken root makes
     * {@code registerPaths()} fail the whole future, silently dropping <b>every</b> root unless it is left out.
     */
    @Test
    void keepsWatchingGoodRootWhenAnotherRootIsMissing(@TempDir Path base) throws Exception {
        Path good = Files.createDirectories(base.resolve("goodProject"));
        Path missing = base.resolve("movedAwayProject");   // Deliberately not created.
        CollectingListener listener = new CollectingListener();
        DirectoryWatcherVfsWatcher watcher = new DirectoryWatcherVfsWatcher((p, d) -> true, listener);
        try {
            watcher.setRoots(Set.of(good, missing));
            awaitWatched(watcher, Set.of(good), 8000);   // The broken root is left out; the good one is watched.

            Files.writeString(good.resolve("a.md"), "x");
            awaitContains(listener.changed, good, 8000);
        } finally {
            Disposer.dispose(watcher);
        }
    }

    /**
     * Once a broken root is back, the same setRoots must rebuild; recording the target as watched on failure would
     * let the equals check block the retry <b>forever</b>.
     */
    @Test
    void retriesRebuildAfterMissingRootReappears(@TempDir Path base) throws Exception {
        Path good = Files.createDirectories(base.resolve("goodProject"));
        Path late = base.resolve("lateProject");         // Doesn't exist yet.
        CollectingListener listener = new CollectingListener();
        DirectoryWatcherVfsWatcher watcher = new DirectoryWatcherVfsWatcher((p, d) -> true, listener);
        try {
            watcher.setRoots(Set.of(good, late));
            awaitWatched(watcher, Set.of(good), 8000);

            Files.createDirectories(late);                // Now it appears.
            watcher.setRoots(Set.of(good, late));         // Same set: the equals check must not skip it.
            awaitWatched(watcher, Set.of(good, late), 8000);

            Files.writeString(late.resolve("b.md"), "y");
            awaitContains(listener.changed, late, 8000);
        } finally {
            Disposer.dispose(watcher);
        }
    }

    @Test
    void ignoresFilteredPaths(@TempDir Path root) throws Exception {
        // onEvent applies the filter, so excluded directories never reach the debounce.
        Files.createDirectories(root.resolve(".myapp"));
        Path docs = Files.createDirectories(root.resolve("docs"));
        Path metadataDir = root.resolve(".myapp").toAbsolutePath().normalize();
        ScanFilter noMetadata = (p, d) -> !p.startsWith(metadataDir);
        CollectingListener listener = new CollectingListener();
        DirectoryWatcherVfsWatcher watcher = new DirectoryWatcherVfsWatcher(noMetadata, listener);
        try {
            watcher.setRoots(Set.of(root));
            awaitWatched(watcher, Set.of(root), 8000);
            Files.writeString(root.resolve(".myapp").resolve("project.sqlite"), "db");
            Files.writeString(docs.resolve("a.md"), "x");
            awaitContains(listener.changed, docs, 8000);
            assertFalse(listener.changed.contains(metadataDir), "an excluded directory must not reach the callback");
        } finally {
            Disposer.dispose(watcher);
        }
    }

    /**
     * A failure while handling events may have lost some, so it falls back to a full rescan as an overflow does. A
     * throwing filter takes the same {@code onException} path as the library's own overflow failure.
     */
    @Test
    void eventHandlingFailureFallsBackToAFullRescan(@TempDir Path root) throws Exception {
        CollectingListener listener = new CollectingListener();
        ScanFilter boom = (p, d) -> {
            throw new IllegalStateException("boom");
        };
        DirectoryWatcherVfsWatcher watcher = new DirectoryWatcherVfsWatcher(boom, listener);
        try {
            watcher.setRoots(Set.of(root));
            awaitWatched(watcher, Set.of(root), 8000);
            Files.writeString(root.resolve("a.md"), "x");
            awaitContains(listener.overflowed, root.toAbsolutePath().normalize(), 8000);
        } finally {
            Disposer.dispose(watcher);
        }
    }

    /** The library's NPE on its own overflow events counts as an overflow; other exceptions stay failures. */
    @Test
    void recognisesTheLibrarysOwnOverflowFailure() {
        NullPointerException overflow = new NullPointerException();
        overflow.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("java.util.concurrent.ConcurrentSkipListMap", "doGet", "ConcurrentSkipListMap.java", 541),
                new StackTraceElement("java.util.concurrent.ConcurrentSkipListMap", "get", "ConcurrentSkipListMap.java", 1312),
                new StackTraceElement("io.methvin.watcher.DirectoryWatcher", "onEvent", "DirectoryWatcher.java", 424),
                new StackTraceElement("io.methvin.watcher.DirectoryWatcher", "runEventLoop", "DirectoryWatcher.java", 327)});
        assertTrue(DirectoryWatcherVfsWatcher.isLibraryOverflow(overflow));

        NullPointerException ours = assertThrows(NullPointerException.class,
                () -> new ConcurrentSkipListMap<Path, String>().get(null));
        assertFalse(DirectoryWatcherVfsWatcher.isLibraryOverflow(ours), "an NPE from our own code is still a failure");

        IllegalStateException other = new IllegalStateException("boom");
        other.setStackTrace(overflow.getStackTrace());
        assertFalse(DirectoryWatcherVfsWatcher.isLibraryOverflow(other), "only an NPE counts");
    }

    /**
     * A deleted root makes the library close and return <b>normally</b>; that still loses the watch, so the watched
     * state must clear and the watcher must rebuild on its own once the folder is back.
     */
    @Test
    void rootTakenAwayStopsWatchingAndComesBackOnItsOwn(@TempDir Path base) throws Exception {
        Path root = Files.createDirectories(base.resolve("proj"));
        CollectingListener listener = new CollectingListener();
        DirectoryWatcherVfsWatcher watcher = new DirectoryWatcherVfsWatcher((p, d) -> true, listener);
        try {
            watcher.setRoots(Set.of(root));
            awaitWatched(watcher, Set.of(root), 8000);

            Files.delete(root);
            awaitWatched(watcher, Set.of(), 15000);

            Files.createDirectories(root);
            awaitWatched(watcher, Set.of(root), 30000);   // The backoff retry resumes without another setRoots.
            listener.changed.clear();
            Files.writeString(root.resolve("a.md"), "x");
            awaitContains(listener.changed, root.toAbsolutePath().normalize(), 8000);
        } finally {
            Disposer.dispose(watcher);
        }
    }

    /**
     * A file that keeps changing, such as a download, must still let a batch flush; a trailing-only debounce would
     * delay changes in <b>every</b> root indefinitely.
     */
    @Test
    void flushesWhileAFileKeepsChanging(@TempDir Path root) throws Exception {
        CollectingListener listener = new CollectingListener();
        DirectoryWatcherVfsWatcher watcher = new DirectoryWatcherVfsWatcher((p, d) -> true, listener);
        AtomicBoolean writing = new AtomicBoolean(true);
        try {
            watcher.setRoots(Set.of(root));
            awaitWatched(watcher, Set.of(root), 8000);
            Thread writer = new Thread(() -> {
                while (writing.get()) {
                    try {
                        Files.writeString(root.resolve("download.part"), String.valueOf(System.nanoTime()));
                        Thread.sleep(50);   // Shorter than the debounce, so each write pushes the trailing flush back.
                    } catch (Exception stop) {
                        return;
                    }
                }
            }, "test-nonstop-writer");
            writer.setDaemon(true);
            writer.start();
            awaitContains(listener.changed, root.toAbsolutePath().normalize(), 6000);
        } finally {
            writing.set(false);
            Disposer.dispose(watcher);
        }
    }

    @Test
    void stopsAfterDispose(@TempDir Path root) throws Exception {
        CollectingListener listener = new CollectingListener();
        DirectoryWatcherVfsWatcher watcher = new DirectoryWatcherVfsWatcher((p, d) -> true, listener);
        watcher.setRoots(Set.of(root));
        awaitWatched(watcher, Set.of(root), 8000);   // Confirm the watch first, or "no callback" passes vacuously.
        Disposer.dispose(watcher);
        listener.changed.clear();
        Files.writeString(root.resolve("after.md"), "x");
        Thread.sleep(1000);
        assertTrue(listener.changed.isEmpty(), "no callbacks after dispose");
    }
}
