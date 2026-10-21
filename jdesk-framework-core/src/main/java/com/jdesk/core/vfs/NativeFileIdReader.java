package com.jdesk.core.vfs;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the native identity a file system gives a file, which doesn't depend on the path and survives renames and
 * moves within a volume. Reconciliation uses it to match a moved file with its old snapshot row and keep its
 * {@link VfsFileId}; a file replaced rather than rewritten in place gets a new value. Compare values for equality
 * only, and treat an empty result as "no clue", never as "different file".
 */
public interface NativeFileIdReader {

    /**
     * Returns the identity, or empty if it can't be read (unsupported platform or file system, no permission, no file).
     * Implementations must encode the volume as well: file indexes are numbered per volume.
     */
    Optional<String> identityOf(Path file);

    /**
     * Returns the identities of all direct children of {@code directory} by name in one pass, as an immutable map that
     * is empty if the directory can't be read or the platform lacks support. The map only speeds things up: for a
     * missing name fall back to {@link #identityOf}; don't treat it as "no identity".
     */
    default Map<String, String> identitiesIn(Path directory) {
        return Map.of();
    }

    /**
     * Looks up where the file with a stored identity is now: at a path, gone or unknown. {@code volumeHint} is any path
     * on the same volume, usually the file's old directory; the default always returns {@link Location#unknown()}.
     * Only {@link Location.Kind#GONE} confirms that the file is gone.
     */
    default Location locate(String identity, Path volumeHint) {
        return Location.unknown();
    }

    /** Result of {@link #locate}; {@code path} is non-null only for {@link Kind#AT}. */
    record Location(Kind kind, Path path) {
        public enum Kind { AT, GONE, UNKNOWN }

        public static Location at(Path path) {
            return new Location(Kind.AT, path);
        }

        public static Location gone() {
            return new Location(Kind.GONE, null);
        }

        public static Location unknown() {
            return new Location(Kind.UNKNOWN, null);
        }
    }

    /**
     * Implementation for unsupported platforms: always empty, so reconciliation relies on paths alone. Before
     * replacing it with {@code BasicFileAttributes.fileKey()} on Unix, check that the inodes of deleted files aren't
     * reused, or pair the key with the creation time.
     */
    NativeFileIdReader UNSUPPORTED = file -> Optional.empty();
}
