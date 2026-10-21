package com.jdesk.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdesk.core.io.AtomicFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/**
 * State storage in an XML file. A save serializes the state, skips the write if the file holds the same bytes, and
 * otherwise replaces the file atomically through {@link AtomicFiles}.
 */
final class FileStateStorage implements StateStorage {

    private static final Logger LOG = LoggerFactory.getLogger(FileStateStorage.class);

    /** Suffix of the corruption backup; one slot, so repeated corruption doesn't pile up backups. */
    private static final String CORRUPT_SUFFIX = ".corrupt";

    private final Path path;
    private final ObjectMapper xmlMapper;

    FileStateStorage(Path path, ObjectMapper xmlMapper) {
        this.path = path;
        this.xmlMapper = xmlMapper;
    }

    @Override
    public Path getPath() {
        return path;
    }

    @Override
    public boolean exists() {
        return Files.isRegularFile(path);
    }

    @Override
    public <T> T loadState(Class<T> stateClass) throws IOException {
        return xmlMapper.readValue(path.toFile(), stateClass);
    }

    @Override
    public boolean saveState(Object state) throws IOException {
        byte[] data = serialize(state);
        if (Files.isRegularFile(path) && Arrays.equals(Files.readAllBytes(path), data)) {
            return false;
        }

        AtomicFiles.write(path, data);
        return true;
    }

    /** Moves the corrupted file to {@code <name>.corrupt}, or the next saveAll would overwrite it with the defaults. */
    @Override
    public boolean backupCorrupted() {
        if (!Files.isRegularFile(path)) {
            return false;
        }
        Path backup = path.resolveSibling(path.getFileName().toString() + CORRUPT_SUFFIX);
        try {
            Files.move(path, backup, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            LOG.warn("Failed to back up corrupted state file {} to {}", path, backup, e);
            return false;
        }
    }

    private byte[] serialize(Object state) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        xmlMapper.writerWithDefaultPrettyPrinter().writeValue(out, state);
        return out.toByteArray();
    }
}
