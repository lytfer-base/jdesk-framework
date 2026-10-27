package com.jdesk.frame.history;

import com.jdesk.core.io.PathAccessPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class HistoryObjectsExistingRootTest {
    @TempDir Path run;

    @Test
    void anObjectWriteCannotRecreateAMovedRootWithoutAnyDatabaseInvolved() throws Exception {
        Path root = Files.createDirectory(run.resolve("project"));
        var store = new HistoryObjectStore(root.resolve("history/objects"));
        Files.move(root, run.resolve("moved"));
        try (var scope = PathAccessPolicy.existingRoot(root).enter()) {
            assertThrows(UncheckedIOException.class, () -> store.store(new byte[]{0, 1, 2}));
        }
        assertFalse(Files.exists(root));
    }

    @Test
    void newPartitionsCanBeCreatedBelowAnExistingRootAndReadBack() throws Exception {
        Path root = Files.createDirectory(run.resolve("project"));
        var store = new HistoryObjectStore(root.resolve("history/objects"));
        byte[] bytes = new byte[]{0, 2, 4, 8, 16};
        try (var scope = PathAccessPolicy.existingRoot(root).enter()) {
            var result = store.store(bytes);
            assertTrue(result.created());
            assertArrayEquals(bytes, store.read(result.hash()).orElseThrow());
            assertFalse(store.store(bytes).created());
        }
    }

    @Test
    void existingObjectsOutsideTheBoundaryAreNotReadOrAcceptedAsStored() throws Exception {
        Path root = Files.createDirectory(run.resolve("project"));
        var outside = new HistoryObjectStore(run.resolve("outside"));
        byte[] bytes = new byte[]{0, 9};
        var result = outside.store(bytes);
        try (var scope = PathAccessPolicy.existingRoot(root).enter()) {
            assertThrows(UncheckedIOException.class, () -> outside.store(bytes));
            assertThrows(UncheckedIOException.class, () -> outside.read(result.hash()));
            assertThrows(UncheckedIOException.class, () -> outside.delete(result.hash()));
        }
        assertArrayEquals(bytes, outside.read(result.hash()).orElseThrow());
    }
}
