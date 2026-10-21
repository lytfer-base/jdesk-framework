package com.jdesk.frame.history;

import com.jdesk.core.io.AtomicFiles;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Content-addressed object store: each distinct content is stored once as a deflated file named by its SHA-256, as
 * in {@code objects/ab/cdef...}. Writes are atomic, so a partial file never passes for an object.
 */
public final class HistoryObjectStore {

    private final Path objectsDir;

    public HistoryObjectStore(Path objectsDir) {
        this.objectsDir = Objects.requireNonNull(objectsDir, "objectsDir").toAbsolutePath().normalize();
    }

    /** Result of a store: object hash, original size, stored size, and whether this call created the object. */
    public record StoredObject(String hash, long size, long storedSize, boolean created) {
    }

    public Path objectsDir() {
        return objectsDir;
    }

    public static String hashOf(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public Path pathOf(String hash) {
        requireHash(hash);
        return objectsDir.resolve(hash.substring(0, 2)).resolve(hash.substring(2));
    }

    public boolean exists(String hash) {
        return Files.isRegularFile(pathOf(hash));
    }

    /** Stores the content; an existing non-empty object isn't rewritten, and its size is reported. */
    public StoredObject store(byte[] data) {
        String hash = hashOf(data);
        Path target = pathOf(hash);
        try {
            long existing = Files.isRegularFile(target) ? Files.size(target) : 0;
            if (existing > 0) {
                return new StoredObject(hash, data.length, existing, false);
            }
            // Must stay atomic: a truncated object would pass as existing and break every version that uses it.
            byte[] packed = deflate(data);
            AtomicFiles.write(target, packed);
            return new StoredObject(hash, data.length, packed.length, true);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write history object: " + target, e);
        }
    }

    /** Deletes stale temp files left by killed processes and returns how many were deleted. */
    public int sweepTempFiles() {
        return AtomicFiles.sweepStaleTempFiles(objectsDir);
    }

    public Optional<byte[]> read(String hash) {
        Path path = pathOf(hash);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(inflate(Files.readAllBytes(path)));
        } catch (IOException | DataFormatException e) {
            throw new UncheckedIOException("Failed to read history object: " + path,
                    e instanceof IOException io ? io : new IOException(e));
        }
    }

    public void delete(String hash) {
        try {
            Files.deleteIfExists(pathOf(hash));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete history object: " + hash, e);
        }
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        try {
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 2));
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static byte[] inflate(byte[] packed) throws DataFormatException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(packed);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, packed.length * 3));
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new DataFormatException("truncated object");
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            inflater.end();
        }
    }

    private static void requireHash(String hash) {
        Objects.requireNonNull(hash, "hash");
        if (hash.length() != 64 || !hash.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
            throw new IllegalArgumentException("bad object hash: " + hash);
        }
    }
}
