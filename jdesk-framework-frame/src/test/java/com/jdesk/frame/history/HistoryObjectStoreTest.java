package com.jdesk.frame.history;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoryObjectStoreTest {

    @TempDir
    Path dir;

    /** An empty object left by a power cut isn't "already stored"; otherwise versions using it couldn't be read. */
    @Test
    void emptyLeftoverObjectIsRewrittenInsteadOfCountedAsAHit() throws IOException {
        HistoryObjectStore store = new HistoryObjectStore(dir.resolve("objects"));
        byte[] data = "power loss\n".repeat(20).getBytes(StandardCharsets.UTF_8);
        String hash = HistoryObjectStore.hashOf(data);
        Path object = store.pathOf(hash);
        Files.createDirectories(object.getParent());
        Files.write(object, new byte[0]);

        HistoryObjectStore.StoredObject stored = store.store(data);

        assertTrue(stored.created(), "an empty leftover must be rewritten");
        assertArrayEquals(data, store.read(hash).orElseThrow());
    }

    @Test
    void storesOnceAndReadsBack() throws IOException {
        HistoryObjectStore store = new HistoryObjectStore(dir.resolve("objects"));
        byte[] data = "hello history\n".repeat(50).getBytes(StandardCharsets.UTF_8);

        HistoryObjectStore.StoredObject first = store.store(data);
        HistoryObjectStore.StoredObject second = store.store(data);

        assertTrue(first.created());
        assertFalse(second.created());
        assertEquals(first.hash(), second.hash());
        assertEquals(HistoryObjectStore.hashOf(data), first.hash());
        assertEquals(data.length, first.size());
        assertTrue(first.storedSize() < data.length, "text should get smaller when compressed");
        assertEquals(store.objectsDir().resolve(first.hash().substring(0, 2)).resolve(first.hash().substring(2)),
                store.pathOf(first.hash()));
        assertArrayEquals(data, store.read(first.hash()).orElseThrow());
        assertEquals(1, listFiles(store.objectsDir()).size(), "same content, one file and no temporary files");
    }

    @Test
    void deleteAndMissingObject() {
        HistoryObjectStore store = new HistoryObjectStore(dir.resolve("objects"));
        String hash = store.store(new byte[] {1, 2, 3}).hash();
        assertTrue(store.exists(hash));
        store.delete(hash);
        assertFalse(store.exists(hash));
        assertTrue(store.read(hash).isEmpty());
        store.delete(hash);
    }

    @Test
    void rejectsMalformedHash() {
        HistoryObjectStore store = new HistoryObjectStore(dir.resolve("objects"));
        assertThrows(IllegalArgumentException.class, () -> store.pathOf("../etc"));
        assertThrows(IllegalArgumentException.class, () -> store.pathOf("ABCD"));
    }

    private static List<Path> listFiles(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).toList();
        }
    }
}
