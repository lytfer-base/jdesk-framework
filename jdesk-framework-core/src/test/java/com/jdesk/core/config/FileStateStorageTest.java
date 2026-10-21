package com.jdesk.core.config;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Write behavior of {@link FileStateStorage}: it replaces the file atomically through {@code AtomicFiles}, skips
 * writing unchanged content and leaves no temporary files behind.
 */
class FileStateStorageTest {

    @TempDir
    Path dir;

    private final XmlMapper xmlMapper = new XmlMapper();

    @Test
    void saveStateWritesContentAndCreatesMissingParent() throws IOException {
        Path target = dir.resolve("nested").resolve("state.xml");
        FileStateStorage storage = new FileStateStorage(target, xmlMapper);

        boolean written = storage.saveState(new SampleState("first"));

        assertTrue(written);
        assertTrue(storage.exists());
        assertEquals("first", storage.loadState(SampleState.class).value);
    }

    @Test
    void saveStateReplacesExistingContent() throws IOException {
        Path target = dir.resolve("state.xml");
        FileStateStorage storage = new FileStateStorage(target, xmlMapper);
        storage.saveState(new SampleState("first"));

        boolean written = storage.saveState(new SampleState("second"));

        assertTrue(written);
        assertEquals("second", storage.loadState(SampleState.class).value);
    }

    @Test
    void unchangedContentSkipsRewrite() throws IOException {
        Path target = dir.resolve("state.xml");
        FileStateStorage storage = new FileStateStorage(target, xmlMapper);
        storage.saveState(new SampleState("same"));

        boolean written = storage.saveState(new SampleState("same"));

        assertFalse(written, "unchanged content should not rewrite the file");
    }

    @Test
    void noTempFilesLeftBehindAfterSave() throws IOException {
        Path target = dir.resolve("state.xml");
        FileStateStorage storage = new FileStateStorage(target, xmlMapper);

        storage.saveState(new SampleState("first"));
        storage.saveState(new SampleState("second"));

        List<Path> leftovers = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            stream.forEach(leftovers::add);
        }
        assertEquals(List.of(target), leftovers,
                "after saving, the directory should hold the target file and no temporary files");
    }

    public static final class SampleState {
        public String value;

        public SampleState() {
        }

        public SampleState(String value) {
            this.value = value;
        }
    }
}
