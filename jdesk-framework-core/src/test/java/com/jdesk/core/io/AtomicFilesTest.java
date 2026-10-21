package com.jdesk.core.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtomicFilesTest {

    @TempDir
    Path dir;

    @Test
    void writesNewFile() throws IOException {
        Path target = dir.resolve("new.txt");

        AtomicFiles.write(target, "你好".getBytes(StandardCharsets.UTF_8));

        assertEquals("你好", Files.readString(target, StandardCharsets.UTF_8));
    }

    @Test
    void replacesExistingFile() throws IOException {
        Path target = dir.resolve("existing.txt");
        Files.writeString(target, "旧内容", StandardCharsets.UTF_8);

        AtomicFiles.write(target, "新内容".getBytes(StandardCharsets.UTF_8));

        assertEquals("新内容", Files.readString(target, StandardCharsets.UTF_8));
    }

    /** Rewriting a file keeps its original creation time, so sorting by creation time isn't upset by every save. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void replacingKeepsTheOriginalCreationTime() throws IOException {
        Path target = Files.writeString(dir.resolve("doc.md"), "旧内容", StandardCharsets.UTF_8);
        FileTime created = FileTime.fromMillis(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000);
        Files.setAttribute(target, "basic:creationTime", created);

        AtomicFiles.write(target, "新内容".getBytes(StandardCharsets.UTF_8));

        assertEquals("新内容", Files.readString(target, StandardCharsets.UTF_8));
        assertEquals(created.toMillis(), creationTimeOf(target).toMillis());
    }

    @Test
    void createsMissingParentDirectories() throws IOException {
        Path target = dir.resolve("a").resolve("b").resolve("deep.txt");

        AtomicFiles.write(target, "x".getBytes(StandardCharsets.UTF_8));

        assertEquals("x", Files.readString(target, StandardCharsets.UTF_8));
    }

    @Test
    void leavesNoTempFileBehind() throws IOException {
        Path target = dir.resolve("clean.txt");

        AtomicFiles.write(target, "内容".getBytes(StandardCharsets.UTF_8));

        assertEquals(List.of("clean.txt"), fileNamesIn(dir));
    }

    /** A failure before the move leaves every byte of the original file untouched. */
    @Test
    void failureBeforeReplaceLeavesTargetUntouched() throws IOException {
        Path target = dir.resolve("guarded.txt");
        Files.writeString(target, "原内容", StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> AtomicFiles.write(target,
                "覆盖内容".getBytes(StandardCharsets.UTF_8),
                () -> {
                    throw new IOException("check before the move failed");
                }));

        assertEquals("原内容", Files.readString(target, StandardCharsets.UTF_8));
        assertEquals(List.of("guarded.txt"), fileNamesIn(dir));
    }

    @Test
    void beforeReplaceRunsWhileTargetStillHoldsOldContent() throws IOException {
        Path target = dir.resolve("ordering.txt");
        Files.writeString(target, "旧", StandardCharsets.UTF_8);
        List<String> seen = new ArrayList<>();

        AtomicFiles.write(target, "新".getBytes(StandardCharsets.UTF_8),
                () -> seen.add(Files.readString(target, StandardCharsets.UTF_8)));

        assertEquals(List.of("旧"), seen);
        assertEquals("新", Files.readString(target, StandardCharsets.UTF_8));
    }

    /**
     * Concurrent writes to one target leave one writer's complete content, never a splice of two.
     * Mutation probe: give the temporary file a fixed name without the unique suffix and this test fails.
     */
    @Test
    void concurrentWritesNeverSplice() throws Exception {
        Path target = dir.resolve("contended.bin");
        int writers = 4;
        int payloadSize = 512 * 1024;

        for (int round = 0; round < 8; round++) {
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(writers);
            List<Throwable> failures = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                byte[] payload = new byte[payloadSize];
                java.util.Arrays.fill(payload, (byte) ('A' + i));
                Thread thread = new Thread(() -> {
                    try {
                        start.await();
                        AtomicFiles.write(target, payload);
                    } catch (Throwable failure) {
                        synchronized (failures) {
                            failures.add(failure);
                        }
                    } finally {
                        done.countDown();
                    }
                }, "atomic-write-" + i);
                thread.setDaemon(true);
                thread.start();
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "concurrent writes did not finish within 30 seconds");
            synchronized (failures) {
                // A move that fails under contention is allowed (the data stays intact); any other exception is not.
                List<Throwable> unexpected = failures.stream()
                        .filter(failure -> !(failure instanceof AccessDeniedException))
                        .toList();
                assertEquals(List.of(), unexpected, "concurrent writes may fail only through contention");
                assertTrue(failures.size() < writers, "round " + round + ": every writer failed");
            }

            byte[] written = Files.readAllBytes(target);
            assertEquals(payloadSize, written.length, "round " + round + ": wrong length, so the file was truncated");
            byte[] expected = new byte[payloadSize];
            java.util.Arrays.fill(expected, written[0]);
            assertArrayEquals(expected, written, "round " + round + ": the content was spliced from several writers");
        }

        assertEquals(List.of("contended.bin"), fileNamesIn(dir));
    }

    /**
     * When another program briefly holds the temporary file without delete sharing, the move retries instead of
     * failing. The releaser closes after about 3 ms; the test can flake only if that outlasts the whole retry budget.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void briefExternalHoldOnTempFileIsRetried() throws Exception {
        Path target = dir.resolve("held.txt");
        List<Thread> releasers = new ArrayList<>();

        try {
            AtomicFiles.write(target, "新内容".getBytes(StandardCharsets.UTF_8), () -> {
                Path temp;
                try (Stream<Path> entries = Files.list(dir)) {
                    temp = entries.filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith(".jdesk-atomic-") && name.endsWith(".tmp");
                    }).findFirst().orElseThrow();
                }
                // FileInputStream opens without delete sharing, so the rename fails as with an external holder.
                FileInputStream hold = new FileInputStream(temp.toFile());
                Thread releaser = new Thread(() -> {
                    try (hold) {
                        Thread.sleep(3);
                    } catch (IOException | InterruptedException ignored) {
                    }
                }, "atomic-temp-releaser");
                releaser.start();
                releasers.add(releaser);
            });
        } finally {
            for (Thread releaser : releasers) {
                releaser.join();
            }
        }

        assertEquals("新内容", Files.readString(target, StandardCharsets.UTF_8));
        assertEquals(List.of("held.txt"), fileNamesIn(dir));
    }

    /**
     * An interrupt while backing off on a held target throws InterruptedIOException and keeps the flag; the target
     * stays as it was and no temporary file is left.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void waitingToMoveIntoPlaceIsInterruptedAsInterruptedIo() throws Exception {
        Path target = Files.writeString(dir.resolve("held.txt"), "原内容", StandardCharsets.UTF_8);

        // FileInputStream opens without delete sharing, so replacing the file is denied and the move backs off.
        try (FileInputStream hold = new FileInputStream(target.toFile())) {
            assertThrows(InterruptedIOException.class, () -> AtomicFiles.write(target,
                    "新内容".getBytes(StandardCharsets.UTF_8), () -> Thread.currentThread().interrupt()));
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag should survive the throw");
        } finally {
            Thread.interrupted();
        }

        assertEquals("原内容", Files.readString(target, StandardCharsets.UTF_8));
        assertEquals(List.of("held.txt"), fileNamesIn(dir));
    }

    /** In a masked section an interrupt during the backoff is deferred: the write completes, then the flag returns. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aMaskedWriteWaitsOutItsBackoff() throws Exception {
        Path target = Files.writeString(dir.resolve("held.txt"), "原内容", StandardCharsets.UTF_8);
        Thread writer = Thread.currentThread();
        FileInputStream hold = new FileInputStream(target.toFile());
        // Release once the writer has taken the interrupt and sleeps in the backoff; the deadline prevents a hang.
        Thread releaser = new Thread(() -> {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((writer.isInterrupted() || writer.getState() != Thread.State.TIMED_WAITING)
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            try {
                hold.close();
            } catch (IOException ignored) {
            }
        }, "atomic-target-releaser");
        releaser.setDaemon(true);

        try {
            Cancellation.uninterruptibly(() -> {
                AtomicFiles.write(target, "新内容".getBytes(StandardCharsets.UTF_8), () -> {
                    writer.interrupt();
                    releaser.start();
                });
                assertFalse(Thread.currentThread().isInterrupted(), "the interrupt should be deferred, not restored");
                return null;
            });
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt should be restored after the section");
        } finally {
            Thread.interrupted();
            releaser.join();
            hold.close();
        }

        assertEquals("新内容", Files.readString(target, StandardCharsets.UTF_8));
        assertEquals(List.of("held.txt"), fileNamesIn(dir));
    }

    /**
     * The startup sweep deletes matching temp files past the age threshold, in nested directories too. Fresh ones
     * (another instance may be writing them), ordinary files and {@code .corrupt} backups are kept.
     */
    @Test
    void sweepRemovesOnlyStaleMatchingTempFiles() throws IOException {
        Path nested = dir.resolve("data").resolve("images");
        Files.createDirectories(nested);

        Path staleTemp = dir.resolve(".jdesk-atomic-123456.tmp");
        Path staleNestedTemp = nested.resolve(".jdesk-atomic-789.tmp");
        Path freshTemp = dir.resolve(".jdesk-atomic-999.tmp");
        Path normalFile = dir.resolve("keep.txt");
        Path corruptBackup = dir.resolve("window.xml.corrupt");

        Files.writeString(staleTemp, "stale", StandardCharsets.UTF_8);
        Files.writeString(staleNestedTemp, "stale-nested", StandardCharsets.UTF_8);
        Files.writeString(freshTemp, "fresh", StandardCharsets.UTF_8);
        Files.writeString(normalFile, "keep", StandardCharsets.UTF_8);
        Files.writeString(corruptBackup, "<broken-xml/>", StandardCharsets.UTF_8);

        backdate(staleTemp);
        backdate(staleNestedTemp);
        // freshTemp keeps the current time.

        int swept = AtomicFiles.sweepStaleTempFiles(dir);

        assertEquals(2, swept);
        assertTrue(Files.notExists(staleTemp), "a temp file past the threshold should be deleted");
        assertTrue(Files.notExists(staleNestedTemp), "a stale temp file in a nested directory should be deleted too");
        assertTrue(Files.exists(freshTemp), "a fresh temp file may belong to another instance and must stay");
        assertTrue(Files.exists(normalFile), "the sweep should leave ordinary files alone");
        assertTrue(Files.exists(corruptBackup), "a .corrupt backup is not a temp file and must be kept");
    }

    @Test
    void sweepToleratesMissingRoot() {
        assertEquals(0, AtomicFiles.sweepStaleTempFiles(dir.resolve("no-such-dir")));
        assertEquals(0, AtomicFiles.sweepStaleTempFiles(null));
    }

    /** An interrupted sweep stops quietly, returns how many files it deleted (none here) and keeps the flag. */
    @Test
    void sweepingStopsQuietlyWhenInterrupted() throws IOException {
        Path staleTemp = staleTempFile(dir.resolve(".jdesk-atomic-1.tmp"));
        Path staleNestedTemp = staleTempFile(Files.createDirectories(dir.resolve("data")).resolve(".jdesk-atomic-2.tmp"));
        int swept;
        try {
            Thread.currentThread().interrupt();

            swept = AtomicFiles.sweepStaleTempFiles(dir);

            assertTrue(Thread.currentThread().isInterrupted(), "stopping quietly must still keep the interrupt flag");
        } finally {
            Thread.interrupted();
        }
        assertEquals(0, swept);
        assertTrue(Files.exists(staleTemp));
        assertTrue(Files.exists(staleNestedTemp));
    }

    /** The sweep doesn't enter junctions below the root, so it never deletes files outside the root. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void sweepDoesNotFollowJunctionsBelowRoot() throws Exception {
        Path root = Files.createDirectories(dir.resolve("root"));
        Path outside = Files.createDirectories(dir.resolve("outside"));
        Path outsideTemp = staleTempFile(outside.resolve(".jdesk-atomic-42.tmp"));
        Path link = createJunction(root.resolve("link"), outside);
        try {
            AtomicFiles.sweepStaleTempFiles(root);

            assertTrue(Files.exists(outsideTemp), "a file reachable only through the junction must stay");
        } finally {
            Files.deleteIfExists(link);
        }
    }

    /** A root that is itself a junction, such as a data directory moved to another drive, is still swept. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void sweepStillSweepsWhenRootItselfIsJunction() throws Exception {
        Path realData = Files.createDirectories(dir.resolve("real-data"));
        Path staleTemp = staleTempFile(realData.resolve(".jdesk-atomic-7.tmp"));
        Path root = createJunction(dir.resolve("data"), realData);
        try {
            assertEquals(1, AtomicFiles.sweepStaleTempFiles(root));
            assertTrue(Files.notExists(staleTemp));
        } finally {
            Files.deleteIfExists(root);
        }
    }

    /** A copy in progress is new but carries its source's old modified time; it isn't a leftover and must stay. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void sweepKeepsACopyInProgressThatCarriesAnOldModifiedTime() throws IOException {
        Path copying = Files.writeString(dir.resolve(".jdesk-atomic-55.tmp"), "copying", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(copying, FileTime.fromMillis(System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1000));

        assertEquals(0, AtomicFiles.sweepStaleTempFiles(dir));
        assertTrue(Files.exists(copying), "a newly created temp file must stay even with an old modified time");
    }

    private static Path staleTempFile(Path file) throws IOException {
        Files.writeString(file, "stale", StandardCharsets.UTF_8);
        backdate(file);
        return file;
    }

    private static FileTime creationTimeOf(Path file) throws IOException {
        return Files.readAttributes(file, BasicFileAttributes.class).creationTime();
    }

    /** Moves the creation and modified times 10 minutes back, like a file left behind by a killed process. */
    private static void backdate(Path file) throws IOException {
        FileTime stale = FileTime.fromMillis(System.currentTimeMillis() - 10 * 60 * 1000L);
        Files.setLastModifiedTime(file, stale);
        Files.setAttribute(file, "basic:creationTime", stale);
    }

    private static Path createJunction(Path link, Path target) throws Exception {
        Process mklink = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true)
                .start();
        mklink.getInputStream().readAllBytes();
        assertEquals(0, mklink.waitFor(), "mklink /J failed: " + link);
        return link;
    }

    private static List<String> fileNamesIn(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
