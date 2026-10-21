package com.jdesk.core.io;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Interruptible reads, copies and walks to use instead of Files.copy, readAllBytes, walkFileTree and transferTo,
 * which ignore interruption. Work is done in chunks with a {@link Cancellation#checkpoint} before each one: an
 * interrupt throws InterruptedIOException and keeps the flag, and inside a masked section the work runs to the end.
 */
public final class InterruptibleFiles {

    private static final Logger LOG = LoggerFactory.getLogger(InterruptibleFiles.class);
    /** The alternate data stream in which Windows records where a file was downloaded from. */
    private static final String ZONE_IDENTIFIER = "Zone.Identifier";

    /** Maximum bytes read or written between two checkpoints. */
    public static final int CHUNK_BYTES = 64 * 1024;

    private static final int MAX_ARRAY_LENGTH = Integer.MAX_VALUE - 8;

    private InterruptibleFiles() {
    }

    /** Copies in chunks with a checkpoint before each and returns the byte count; closes neither stream. */
    public static long copy(InputStream in, OutputStream out) throws IOException {
        return transfer(in, out, "copying a stream");
    }

    /**
     * Copies through a temporary file beside {@code target}, with a checkpoint before each chunk, and moves it into
     * place; the temporary file is deleted on failure. Keeps the source's origin ({@link #keepOrigin}) but no other
     * attributes, never creates parent directories, and treats a link at {@code target} as the link itself.
     *
     * @throws SourceIsDirectoryException if the source is a directory; other non-regular files are copied
     * @throws FileAlreadyExistsException if {@code replace} is false and the target exists
     */
    public static void copy(Path source, Path target, boolean replace) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        BasicFileAttributes attributes = Files.readAttributes(source, BasicFileAttributes.class);
        if (attributes.isDirectory()) {
            throw new SourceIsDirectoryException(source.toString());
        }
        if (!replace && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(target.toString());
        }
        Path temp = AtomicFiles.createTempBeside(target);
        try {
            try (InputStream in = Files.newInputStream(source);
                 OutputStream out = Files.newOutputStream(temp)) {
                transfer(in, out, "copying " + source);
            }
            keepOrigin(source, temp);
            AtomicFiles.moveIntoPlace(temp, target, replace);
        } finally {
            AtomicFiles.deleteLeftover(temp);
        }
    }

    /**
     * Carries the source's "downloaded from the internet" marker (the Windows {@code Zone.Identifier} stream) and its
     * modified time over to {@code copy}; best effort, failures are only logged.
     * Call it wherever user files are copied chunk by chunk, and set the modified time last: writing resets it.
     */
    public static void keepOrigin(Path source, Path copy) {
        keepZoneIdentifier(source, copy);
        try {
            Files.setLastModifiedTime(copy, Files.getLastModifiedTime(source));
        } catch (IOException failure) {
            LOG.warn("interruptibleFiles.keepModifiedTimeFailed target={}", copy, failure);
        }
    }

    private static void keepZoneIdentifier(Path source, Path copy) {
        UserDefinedFileAttributeView from = Files.getFileAttributeView(source, UserDefinedFileAttributeView.class);
        UserDefinedFileAttributeView to = Files.getFileAttributeView(copy, UserDefinedFileAttributeView.class);
        if (from == null || to == null) {
            return;
        }
        try {
            if (!from.list().contains(ZONE_IDENTIFIER)) {
                return;
            }
            ByteBuffer marker = ByteBuffer.allocate(from.size(ZONE_IDENTIFIER));
            from.read(ZONE_IDENTIFIER, marker);
            marker.flip();
            to.write(ZONE_IDENTIFIER, marker);
        } catch (IOException | RuntimeException failure) {
            LOG.warn("interruptibleFiles.keepZoneIdentifierFailed target={}", copy, failure);
        }
    }

    /** Reads the whole file with a checkpoint before each chunk. */
    public static byte[] readAllBytes(Path file) throws IOException {
        String doing = "reading " + file;
        try (InputStream in = Files.newInputStream(file)) {
            long size = Files.size(file);
            if (size > MAX_ARRAY_LENGTH) {
                throw new OutOfMemoryError("Required array size too large: " + file);
            }
            byte[] buffer = new byte[(int) size];
            int length = 0;
            for (;;) {
                Cancellation.checkpoint(doing);
                if (length < buffer.length) {
                    int n = in.read(buffer, length, Math.min(CHUNK_BYTES, buffer.length - length));
                    if (n < 0) {
                        return Arrays.copyOf(buffer, length);
                    }
                    length += n;
                    continue;
                }
                // Buffer full: probe one more byte and grow only if the stream isn't at its end.
                int next = in.read();
                if (next < 0) {
                    return buffer;
                }
                buffer = Arrays.copyOf(buffer, grownLength(length));
                buffer[length++] = (byte) next;
            }
        }
    }

    /** Interruptible {@link Files#walkFileTree}: checkpoints before entering each directory and visiting each file. */
    public static Path walkFileTree(Path start, Set<FileVisitOption> options, int maxDepth,
                                    FileVisitor<? super Path> visitor) throws IOException {
        Objects.requireNonNull(visitor, "visitor");
        return Files.walkFileTree(start, options, maxDepth, new CheckpointedVisitor(visitor, "walking " + start));
    }

    /** Like {@link #walkFileTree(Path, Set, int, FileVisitor)}, without following links and with no depth limit. */
    public static Path walkFileTree(Path start, FileVisitor<? super Path> visitor) throws IOException {
        return walkFileTree(start, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE, visitor);
    }

    private static long transfer(InputStream in, OutputStream out, String doing) throws IOException {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(out, "out");
        byte[] chunk = new byte[CHUNK_BYTES];
        long total = 0;
        for (;;) {
            Cancellation.checkpoint(doing);
            int n = in.read(chunk);
            if (n < 0) {
                return total;
            }
            out.write(chunk, 0, n);
            total += n;
        }
    }

    private static int grownLength(int length) {
        if (length >= MAX_ARRAY_LENGTH) {
            throw new OutOfMemoryError("Required array size too large");
        }
        return (int) Math.min(MAX_ARRAY_LENGTH, Math.max(2L * length, CHUNK_BYTES));
    }

    /** Runs a checkpoint before every callback except {@code postVisitDirectory}, then delegates. */
    private static final class CheckpointedVisitor implements FileVisitor<Path> {
        private final FileVisitor<? super Path> visitor;
        private final String doing;

        CheckpointedVisitor(FileVisitor<? super Path> visitor, String doing) {
            this.visitor = visitor;
            this.doing = doing;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
            Cancellation.checkpoint(doing);
            return visitor.preVisitDirectory(dir, attrs);
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            Cancellation.checkpoint(doing);
            return visitor.visitFile(file, attrs);
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
            Cancellation.checkpoint(doing);
            return visitor.visitFileFailed(file, failure);
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
            return visitor.postVisitDirectory(dir, failure);
        }
    }
}
