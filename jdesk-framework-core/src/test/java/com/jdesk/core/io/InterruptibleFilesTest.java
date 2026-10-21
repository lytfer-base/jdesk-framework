package com.jdesk.core.io;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.nio.ByteBuffer;
import java.nio.file.attribute.UserDefinedFileAttributeView;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterruptibleFilesTest {

    private static final int CHUNK = InterruptibleFiles.CHUNK_BYTES;

    @TempDir
    Path dir;

    /** A stream copy returns the byte count; an interrupt after the first chunk stops it before the next one. */
    @Test
    void copyStreamStopsBetweenChunks() throws IOException {
        byte[] data = patterned(3 * CHUNK);
        ByteArrayOutputStream whole = new ByteArrayOutputStream();
        assertEquals(data.length, InterruptibleFiles.copy(new ByteArrayInputStream(data), whole));
        assertArrayEquals(data, whole.toByteArray());

        InputStream in = new FilterInputStream(new ByteArrayInputStream(data)) {
            private long delivered;

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                int n = super.read(buffer, offset, length);
                if (n > 0 && (delivered += n) >= CHUNK) {
                    Thread.currentThread().interrupt();
                }
                return n;
            }
        };
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            assertThrows(InterruptedIOException.class, () -> InterruptibleFiles.copy(in, out));
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag should survive the throw");
        } finally {
            Thread.interrupted();
        }
        assertTrue(out.size() <= CHUNK, "no further chunk after the interrupt; wrote " + out.size());
    }

    /** An interrupted copy to a new target leaves neither the target nor a temporary file. */
    @Test
    void copyFileLeavesNoTempAndNoTargetWhenInterrupted() throws IOException {
        Path source = Files.write(dir.resolve("source.bin"), patterned(3 * CHUNK));
        Path targetDir = Files.createDirectories(dir.resolve("out"));
        Path target = targetDir.resolve("copy.bin");
        try {
            Thread.currentThread().interrupt();

            assertThrows(InterruptedIOException.class, () -> InterruptibleFiles.copy(source, target, false));
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag should survive the throw");
        } finally {
            Thread.interrupted();
        }
        assertTrue(Files.notExists(target));
        assertEquals(List.of(), fileNamesIn(targetDir));
    }

    /** An interrupted copy over an existing target leaves it byte for byte intact and no temporary file. */
    @Test
    void copyFileOverAnExistingTargetKeepsItIntactWhenInterrupted() throws IOException {
        Path source = Files.write(dir.resolve("source.bin"), patterned(3 * CHUNK));
        Path target = Files.writeString(dir.resolve("target.txt"), "原内容", StandardCharsets.UTF_8);
        try {
            Thread.currentThread().interrupt();

            assertThrows(InterruptedIOException.class, () -> InterruptibleFiles.copy(source, target, true));
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag should survive the throw");
        } finally {
            Thread.interrupted();
        }
        assertEquals("原内容", Files.readString(target, StandardCharsets.UTF_8));
        assertEquals(List.of("source.bin", "target.txt"), fileNamesIn(dir));
    }

    /**
     * Without replace, an existing target makes both the up-front check and the final move throw
     * FileAlreadyExistsException and stays as it was; with replace, it gets the source content.
     */
    @Test
    void copyFileRefusesAnExistingTargetUnlessReplacing() throws IOException {
        Path source = Files.writeString(dir.resolve("source.txt"), "新内容", StandardCharsets.UTF_8);
        Path target = Files.writeString(dir.resolve("target.txt"), "原内容", StandardCharsets.UTF_8);

        assertThrows(FileAlreadyExistsException.class, () -> InterruptibleFiles.copy(source, target, false));
        assertEquals("原内容", Files.readString(target, StandardCharsets.UTF_8));

        // A target that appears after the up-front check isn't overwritten by the move either; the exception names it.
        Path staged = Files.writeString(AtomicFiles.createTempBeside(target), "晚到的内容", StandardCharsets.UTF_8);
        FileAlreadyExistsException taken = assertThrows(FileAlreadyExistsException.class,
                () -> AtomicFiles.moveIntoPlace(staged, target, false));
        assertEquals(target.toString(), taken.getFile());
        assertEquals("原内容", Files.readString(target, StandardCharsets.UTF_8));
        Files.delete(staged);

        InterruptibleFiles.copy(source, target, true);
        assertEquals("新内容", Files.readString(target, StandardCharsets.UTF_8));
        assertEquals(List.of("source.txt", "target.txt"), fileNamesIn(dir));
    }

    /** A folder source throws SourceIsDirectoryException, not a bare FileSystemException, and leaves nothing behind. */
    @Test
    void copyFileRejectsAFolderSourceWithItsOwnType() throws IOException {
        Path folder = Files.createDirectories(dir.resolve("folder"));
        Path target = dir.resolve("copy.bin");

        SourceIsDirectoryException failure = assertThrows(SourceIsDirectoryException.class,
                () -> InterruptibleFiles.copy(folder, target, false));

        assertEquals(folder.toString(), failure.getFile());
        assertTrue(Files.notExists(target));
        assertEquals(List.of("folder"), fileNamesIn(dir));
    }

    /** A copy has the full content and the source's modified time, but not attributes such as read-only. */
    @Test
    void copyFileKeepsTheSourceModifiedTime() throws IOException {
        byte[] content = patterned(2 * CHUNK + 17);
        Path source = Files.write(dir.resolve("photo.jpg"), content);
        FileTime modified = FileTime.fromMillis(1_600_000_000_000L);
        Files.setLastModifiedTime(source, modified);
        DosFileAttributeView dos = Files.getFileAttributeView(source, DosFileAttributeView.class);
        if (dos != null) {
            dos.setReadOnly(true);
        }
        Path target = dir.resolve("copy.jpg");
        try {
            InterruptibleFiles.copy(source, target, false);
        } finally {
            if (dos != null) {
                dos.setReadOnly(false);
            }
        }

        assertArrayEquals(content, Files.readAllBytes(target));
        assertEquals(modified.toMillis(), Files.getLastModifiedTime(target).toMillis());
        if (dos != null) {
            assertFalse(Files.readAttributes(target, DosFileAttributes.class).isReadOnly(), "read-only isn't copied");
        }
        assertEquals(List.of("copy.jpg", "photo.jpg"), fileNamesIn(dir));
    }

    /** readAllBytes reads everything; when already interrupted it throws InterruptedIOException and keeps the flag. */
    @Test
    void readAllBytesStopsWhenInterrupted() throws IOException {
        byte[] content = patterned(3 * CHUNK + 5);
        Path file = Files.write(dir.resolve("big.bin"), content);
        assertArrayEquals(content, InterruptibleFiles.readAllBytes(file));
        assertArrayEquals(new byte[0], InterruptibleFiles.readAllBytes(Files.write(dir.resolve("empty.bin"), new byte[0])));
        try {
            Thread.currentThread().interrupt();

            assertThrows(InterruptedIOException.class, () -> InterruptibleFiles.readAllBytes(file));
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag should survive the throw");
        } finally {
            Thread.interrupted();
        }
    }

    /** An interrupt after a directory or a file stops the walk before the next directory or file. */
    @Test
    void walkStopsBeforeTheNextDirectory() throws IOException {
        Path root = Files.createDirectories(dir.resolve("root"));
        for (String name : List.of("a", "b", "c")) {
            Path folder = Files.createDirectories(root.resolve(name));
            Files.writeString(folder.resolve("1.txt"), name);
            Files.writeString(folder.resolve("2.txt"), name);
        }
        List<Path> entered = new ArrayList<>();
        List<Path> visited = new ArrayList<>();

        try {
            assertThrows(InterruptedIOException.class, () -> InterruptibleFiles.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path folder, BasicFileAttributes attrs) {
                    entered.add(folder);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path folder, IOException failure) {
                    Thread.currentThread().interrupt();
                    return FileVisitResult.CONTINUE;
                }
            }));
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag should survive the throw");
        } finally {
            Thread.interrupted();
        }
        assertEquals(2, entered.size(), "only the root and the first subdirectory should be entered: " + entered);

        try {
            assertThrows(InterruptedIOException.class, () -> InterruptibleFiles.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    visited.add(file);
                    Thread.currentThread().interrupt();
                    return FileVisitResult.CONTINUE;
                }
            }));
        } finally {
            Thread.interrupted();
        }
        assertEquals(1, visited.size(), "only the first file should be visited: " + visited);
    }

    private static byte[] patterned(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (i * 31 + i / CHUNK);
        }
        return bytes;
    }

    private static List<String> fileNamesIn(Path folder) throws IOException {
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    /**
     * A copy keeps the source's mark of the web (the Zone.Identifier stream) and its modified time; a source without
     * the mark gives a copy without it.
     */
    @Test
    void copyFileCarriesTheMarkOfTheWeb(@TempDir Path dir) throws IOException {
        Path marked = Files.write(dir.resolve("download.docm"), new byte[]{1, 2, 3});
        Path plain = Files.write(dir.resolve("plain.docm"), new byte[]{4});
        UserDefinedFileAttributeView view = Files.getFileAttributeView(marked, UserDefinedFileAttributeView.class);
        Assumptions.assumeTrue(view != null, "this file system has no alternate data streams");
        byte[] zone = "[ZoneTransfer]\r\nZoneId=3\r\n".getBytes(StandardCharsets.US_ASCII);
        view.write("Zone.Identifier", ByteBuffer.wrap(zone));
        FileTime downloaded = FileTime.fromMillis(1_600_000_000_000L);
        Files.setLastModifiedTime(marked, downloaded);

        InterruptibleFiles.copy(marked, dir.resolve("copy.docm"), false);
        InterruptibleFiles.copy(plain, dir.resolve("plain-copy.docm"), false);

        UserDefinedFileAttributeView copied = Files.getFileAttributeView(dir.resolve("copy.docm"), UserDefinedFileAttributeView.class);
        ByteBuffer read = ByteBuffer.allocate(copied.size("Zone.Identifier"));
        copied.read("Zone.Identifier", read);
        assertArrayEquals(zone, read.array());
        assertEquals(downloaded.toMillis(), Files.getLastModifiedTime(dir.resolve("copy.docm")).toMillis());
        assertFalse(Files.getFileAttributeView(dir.resolve("plain-copy.docm"), UserDefinedFileAttributeView.class)
                .list().contains("Zone.Identifier"));
    }
}
