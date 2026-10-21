package com.jdesk.core.io;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Atomic file writes: the data goes to a unique temporary file in the target's directory, is flushed to disk and then
 * moved into place, so a failure, a killed process or a power cut leaves either the old or the new content. A failed
 * flush is logged as WARN and the move still happens.
 * Every write that replaces an existing file must go through here; don't use {@code Files.write}, which truncates first.
 */
public final class AtomicFiles {

    private static final Logger LOG = LoggerFactory.getLogger(AtomicFiles.class);

    private static final String TEMP_PREFIX = ".jdesk-atomic-";
    private static final String TEMP_SUFFIX = ".tmp";
    private static final int REPLACE_RETRIES = 6;
    private static final long MAX_REPLACE_BACKOFF_MILLIS = 16;

    /** The sweep deletes temp files created and last modified before this age; don't shorten it or check the modified time alone (a copy in progress carries its source's). */
    private static final long STALE_TEMP_THRESHOLD_MILLIS = 5 * 60 * 1000L;

    /** Check run after the temporary file is written and before it replaces the target; throwing abandons the write. */
    @FunctionalInterface
    public interface BeforeReplace {
        void check() throws IOException;
    }

    private AtomicFiles() {
    }

    /**
     * Recursively deletes {@code .jdesk-atomic-*.tmp} files under {@code root} left by killed processes once they are
     * past the age threshold, and returns how many were deleted. Best effort: failures are only logged, an interrupt
     * stops the sweep early, a missing {@code root} returns 0, and links under the root aren't entered ({@link FileLinks}).
     */
    public static int sweepStaleTempFiles(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return 0;
        }
        long staleBefore = System.currentTimeMillis() - STALE_TEMP_THRESHOLD_MILLIS;
        Sweeper sweeper = new Sweeper(root, staleBefore);
        try {
            InterruptibleFiles.walkFileTree(root, sweeper);
        } catch (IOException e) {
            if (Cancellation.isCancellation(e)) {
                LOG.debug("atomicFiles.tempSweepCancelled root={} swept={}", root, sweeper.swept);
            } else {
                LOG.warn("atomicFiles.tempSweepFailed root={}", root, e);
            }
        }
        return sweeper.swept;
    }

    private static final class Sweeper extends SimpleFileVisitor<Path> {
        private final Path root;
        private final long staleBefore;
        private int swept;

        Sweeper(Path root, long staleBefore) {
            this.root = root;
            this.staleBefore = staleBefore;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            // Don't enter links: a junction still reports as a directory, and walking into it leaves the root or loops.
            return !dir.equals(root) && FileLinks.isLink(dir, attrs) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            String name = file.getFileName().toString();
            if (name.startsWith(TEMP_PREFIX) && name.endsWith(TEMP_SUFFIX)
                    && Math.max(attrs.lastModifiedTime().toMillis(), attrs.creationTime().toMillis()) < staleBefore) {
                try {
                    Files.deleteIfExists(file);
                    swept++;
                    LOG.info("Swept stale atomic temp file: {}", file);
                } catch (IOException e) {
                    LOG.warn("atomicFiles.tempSweepDeleteFailed file={}", file, e);
                }
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException e) {
            LOG.warn("atomicFiles.tempSweepVisitFailed file={}", file, e);
            return FileVisitResult.CONTINUE;
        }
    }

    /** Atomically writes {@code target}, creating missing parent directories. */
    public static void write(Path target, byte[] bytes) throws IOException {
        write(target, bytes, null);
    }

    /**
     * Like {@link #write(Path, byte[])}, calling {@code beforeReplace} once the temporary file is ready; if it throws,
     * the write is abandoned and the target stays as it was. Replacing a file keeps its creation time.
     */
    public static void write(Path target, byte[] bytes, BeforeReplace beforeReplace) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(bytes, "bytes");
        Files.createDirectories(parentOf(target));
        Path temp = createTempBeside(target);
        try {
            writeAndForce(temp, bytes);
            if (beforeReplace != null) {
                beforeReplace.check();
            }
            keepCreationTime(target, temp);
            moveIntoPlace(temp, target, true);
        } finally {
            deleteLeftover(temp);
        }
    }

    /** Gives the temp file the existing target's creation time; a missing target is skipped and failures are only logged. */
    private static void keepCreationTime(Path target, Path temp) {
        try {
            FileTime created = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                    .creationTime();
            Files.getFileAttributeView(temp, BasicFileAttributeView.class).setTimes(null, null, created);
        } catch (NoSuchFileException absent) {
            // New file.
        } catch (IOException failure) {
            LOG.warn("atomicFiles.keepCreationTimeFailed target={}", target, failure);
        }
    }

    /** Creates a unique {@code .jdesk-atomic-*.tmp} beside {@code target}; the parent directory must exist. */
    static Path createTempBeside(Path target) throws IOException {
        // Must share the target's directory: a move across file systems isn't atomic and degrades to copy plus delete.
        return Files.createTempFile(parentOf(target), TEMP_PREFIX, TEMP_SUFFIX);
    }

    private static Path parentOf(Path target) throws IOException {
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("Target has no parent directory to stage the temporary file in: " + target);
        }
        return parent;
    }

    private static void writeAndForce(Path temp, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            try {
                channel.force(true);
            } catch (IOException e) {
                LOG.warn("atomicFiles.forceFailed temp={}", temp, e);
            }
        }
    }

    /** Deletes a leftover temp file. Must not throw: it runs in {@code finally}, where it would hide the real result. */
    static void deleteLeftover(Path temp) {
        try {
            Files.deleteIfExists(temp);
        } catch (IOException failure) {
            LOG.warn("atomicFiles.tempCleanupFailed temp={}", temp, failure);
        }
    }

    /**
     * Moves the temp file into place, retrying briefly while the target or the temp file is held by another program.
     * Throws FileAlreadyExistsException if {@code replace} is false and the target exists; an interrupted backoff keeps
     * waiting inside a masked section and otherwise throws InterruptedIOException, keeping the flag.
     */
    static void moveIntoPlace(Path temp, Path target, boolean replace) throws IOException {
        long backoffMillis = 1;
        for (int attempt = 0; ; attempt++) {
            try {
                if (replace) {
                    replace(temp, target);
                } else {
                    Files.move(temp, target);
                }
                return;
            } catch (FileAlreadyExistsException taken) {
                throw new FileAlreadyExistsException(target.toString());
            } catch (FileSystemException contended) {
                if (contended instanceof NoSuchFileException || attempt >= REPLACE_RETRIES) {
                    throw contended;
                }
            }
            backOff(backoffMillis, target);
            backoffMillis = Math.min(backoffMillis * 2, MAX_REPLACE_BACKOFF_MILLIS);
        }
    }

    private static void backOff(long millis, Path target) throws InterruptedIOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        long remaining;
        while ((remaining = deadline - System.nanoTime()) > 0) {
            try {
                TimeUnit.NANOSECONDS.sleep(remaining);
            } catch (InterruptedException interrupted) {
                if (!Cancellation.masked()) {
                    Thread.currentThread().interrupt();
                    InterruptedIOException failure =
                            new InterruptedIOException("Interrupted while waiting to move into place: " + target);
                    failure.initCause(interrupted);
                    throw failure;
                }
                Cancellation.deferInterrupt();
            }
        }
    }

    private static void replace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException fallback) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
