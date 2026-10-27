package com.jdesk.core.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class AtomicFilesExistingRootTest {
    @TempDir Path run;

    @Test
    void movedRootFailsBeforeTheCommitCallbackAndIsNotRecreated() throws Exception {
        Path root = Files.createDirectory(run.resolve("project"));
        Path parent = Files.createDirectory(root.resolve("docs"));
        Path target = parent.resolve("note.md");
        Files.writeString(target, "original");
        Path moved = run.resolve("moved");
        Files.move(root, moved);
        AtomicBoolean called = new AtomicBoolean();
        try (var scope = PathAccessPolicy.existingRoot(root).enter()) {
            assertThrows(IOException.class, () -> AtomicFiles.write(target, "late".getBytes(), () -> {
                called.set(true);
                throw new IOException("reject commit");
            }));
        }
        assertFalse(called.get(), "root validation must precede staging and the commit callback");
        assertFalse(Files.exists(root));
        assertEquals("original", Files.readString(moved.resolve("docs/note.md")));
    }

    @Test
    void strictWriteDoesNotCreateAMissingParentInsideTheRoot() throws Exception {
        Path root = Files.createDirectory(run.resolve("project"));
        try (var scope = PathAccessPolicy.existingRoot(root).enter()) {
            assertThrows(IOException.class, () -> AtomicFiles.write(root.resolve("missing/note.md"), new byte[]{1}));
        }
        assertFalse(Files.exists(root.resolve("missing")));
    }

    @Test
    void strictWriteRejectsATargetOutsideItsRoot() throws Exception {
        Path root = Files.createDirectory(run.resolve("project"));
        try (var scope = PathAccessPolicy.existingRoot(root).enter()) {
            assertThrows(IOException.class, () -> AtomicFiles.write(run.resolve("outside.txt"), new byte[]{1}));
        }
        assertFalse(Files.exists(run.resolve("outside.txt")));
    }

    @Test
    void strictReplacementPreservesContentOnFailureAndCreationTimeOnSuccess() throws Exception {
        Path target = Files.writeString(run.resolve("note.md"), "original");
        var created = Files.readAttributes(target, BasicFileAttributes.class).creationTime();
        try (var scope = PathAccessPolicy.existingRoot(run).enter()) {
            assertThrows(IOException.class, () -> AtomicFiles.write(target, "rejected".getBytes(), () -> {
                throw new IOException("reject commit");
            }));
            assertEquals("original", Files.readString(target));
            AtomicFiles.write(target, "accepted".getBytes());
        }
        assertEquals("accepted", Files.readString(target));
        assertEquals(created, Files.readAttributes(target, BasicFileAttributes.class).creationTime());
        try (var entries = Files.list(run)) {
            assertEquals(1, entries.count(), "failed staging must leave no temporary file");
        }
    }

    @Test
    void defaultWriteStillCreatesParents() throws Exception {
        Path target = run.resolve("new/parents/note.md");
        AtomicFiles.write(target, "default".getBytes());
        assertEquals("default", Files.readString(target));
    }
}
