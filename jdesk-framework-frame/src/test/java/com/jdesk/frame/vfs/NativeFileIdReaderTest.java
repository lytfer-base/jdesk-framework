package com.jdesk.frame.vfs;

import com.jdesk.core.vfs.NativeFileIdReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests the native file identity contract on the real file system (NTFS): the identity stays when the path changes,
 * which is what makes it useful as a reconcile hint.
 */
class NativeFileIdReaderTest {

    @TempDir
    Path tempDir;

    private final NativeFileIdReader reader = NativeFileIdReaders.forCurrentPlatform();

    @Test
    void identitySurvivesRename() throws Exception {
        Path file = tempDir.resolve("before.txt");
        Files.writeString(file, "x");
        Optional<String> before = reader.identityOf(file);
        assertTrue(before.isPresent(), "this machine uses NTFS, so the native identity should be available");

        Path renamed = tempDir.resolve("after.txt");
        Files.move(file, renamed);

        assertEquals(before, reader.identityOf(renamed), "a rename should not change the native identity");
    }

    /** A path beyond MAX_PATH still yields an identity; without long-path awareness that needs the long-path prefix. */
    @Test
    void deepPathsBeyondMaxPathStillYieldIdentity() throws Exception {
        Path dir = tempDir;
        while (dir.toAbsolutePath().toString().length() < 300) {
            dir = dir.resolve("deep-directory-name-0123456789");
        }
        Files.createDirectories(dir);
        Path file = dir.resolve("note.md");
        Files.writeString(file, "x");

        Optional<String> single = reader.identityOf(file);
        assertTrue(single.isPresent(), "a deep file should yield a native identity too");
        assertEquals(single.orElseThrow(), reader.identitiesIn(dir).get("note.md"), "the bulk lookup should agree");
    }

    @Test
    void identitySurvivesMoveWithinSameVolume() throws Exception {
        Path file = tempDir.resolve("note.txt");
        Files.writeString(file, "x");
        Optional<String> before = reader.identityOf(file);

        Path sub = Files.createDirectories(tempDir.resolve("deep").resolve("er"));
        Path moved = sub.resolve("note.txt");
        Files.move(file, moved);

        assertEquals(before, reader.identityOf(moved), "a move within the volume should keep the native identity");
    }

    @Test
    void identitySurvivesInPlaceRewrite() throws Exception {
        Path file = tempDir.resolve("edited.txt");
        Files.writeString(file, "v1");
        Optional<String> before = reader.identityOf(file);

        // An in-place rewrite, as most editors do, must keep the identity.
        Files.writeString(file, "v2-longer-content");

        assertEquals(before, reader.identityOf(file), "rewriting in place should not change the native identity");
    }

    @Test
    void replacedFileGetsANewIdentity() throws Exception {
        Path file = tempDir.resolve("replaced.txt");
        Files.writeString(file, "v1");
        Optional<String> before = reader.identityOf(file);

        // Replace via a temp file, as editors and git do: the new file must not pass for the old one.
        Path tmp = tempDir.resolve("replaced.txt.tmp");
        Files.writeString(tmp, "v2");
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);

        assertNotEquals(before, reader.identityOf(file), "a replaced file is a new file with a new identity");
    }

    @Test
    void differentFilesGetDifferentIdentities() throws Exception {
        Path a = tempDir.resolve("a.txt");
        Path b = tempDir.resolve("b.txt");
        Files.writeString(a, "same");
        Files.writeString(b, "same");

        assertNotEquals(reader.identityOf(a), reader.identityOf(b),
                "two files with the same content are still two files with different identities");
    }

    @Test
    void directoriesHaveIdentityToo() throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("folder"));
        assertTrue(reader.identityOf(dir).isPresent(), "directories need an identity too");
    }

    @Test
    void lockedAndReadOnlyFilesStillYieldIdentity() throws Exception {
        Path locked = tempDir.resolve("locked.txt");
        Files.writeString(locked, "x");
        try (RandomAccessFile raf = new RandomAccessFile(locked.toFile(), "rw");
             FileLock ignored = raf.getChannel().lock()) {
            assertTrue(reader.identityOf(locked).isPresent(), "a locked file should still yield an identity");
        }

        Path readOnly = tempDir.resolve("readonly.txt");
        Files.writeString(readOnly, "x");
        Files.setAttribute(readOnly, "dos:readonly", true);
        try {
            assertTrue(reader.identityOf(readOnly).isPresent(), "a read-only file should yield an identity too");
        } finally {
            Files.setAttribute(readOnly, "dos:readonly", false);
        }
    }

    /**
     * Bulk and per-file lookups must agree; otherwise identities stored by a scan won't match those computed when
     * pairing, and renames are recognized only sometimes.
     */
    @Test
    void bulkIdentitiesAgreeWithPerFileLookup() throws Exception {
        Files.writeString(tempDir.resolve("a.md"), "one");
        Files.writeString(tempDir.resolve("b.txt"), "two");
        Files.createDirectories(tempDir.resolve("sub"));
        Files.writeString(tempDir.resolve("sub").resolve("deep.md"), "three");

        Map<String, String> bulk = reader.identitiesIn(tempDir);
        assumeTrue(!bulk.isEmpty(), "bulk identity lookup isn't supported on this machine");

        // Direct children only: sub itself is listed, but not sub/deep.md.
        assertEquals(Set.of("a.md", "b.txt", "sub"), bulk.keySet(),
                "should hold only direct children, without . and ..");
        for (Map.Entry<String, String> entry : bulk.entrySet()) {
            assertEquals(reader.identityOf(tempDir.resolve(entry.getKey())).orElseThrow(),
                    entry.getValue(),
                    "the identity of " + entry.getKey() + " must match between bulk and per-file lookups");
        }
    }

    @Test
    void bulkIdentitiesSurviveRename() throws Exception {
        Path file = tempDir.resolve("before.md");
        Files.writeString(file, "x");
        Map<String, String> before = reader.identitiesIn(tempDir);
        assumeTrue(!before.isEmpty(), "bulk identity lookup isn't supported on this machine");

        Files.move(file, tempDir.resolve("after.md"));
        Map<String, String> after = reader.identitiesIn(tempDir);

        assertEquals(before.get("before.md"), after.get("after.md"),
                "the bulk identity must not change on rename either");
    }

    @Test
    void bulkIdentitiesOnMissingDirectoryYieldEmpty() {
        assertTrue(reader.identitiesIn(tempDir.resolve("nope")).isEmpty(),
                "a missing directory gives an empty map without throwing");
    }

    @Test
    void missingFileYieldsEmptyRatherThanThrowing() {
        assertTrue(reader.identityOf(tempDir.resolve("nope.txt")).isEmpty(),
                "a missing file gives empty without throwing; files may vanish during a scan");
    }

    @Test
    void locateFindsWhereAKnownIdentityLivesNow() throws Exception {
        Path file = tempDir.resolve("before.txt");
        Files.writeString(file, "x");
        Optional<String> identity = reader.identityOf(file);
        assumeTrue(identity.isPresent(), "native file identity isn't available on this machine");
        Path moved = Files.createDirectories(tempDir.resolve("moved")).resolve("after.txt");
        Files.move(file, moved);

        NativeFileIdReader.Location location = reader.locate(identity.get(), tempDir);

        assertEquals(NativeFileIdReader.Location.Kind.AT, location.kind(), "a moved file should be found by identity");
        assertEquals(moved.toRealPath(), location.path().toRealPath(), "it should be found at its current path");
    }

    @Test
    void locateReportsGoneOnceTheFileIsDeleted() throws Exception {
        Path file = tempDir.resolve("doomed.txt");
        Files.writeString(file, "x");
        Optional<String> identity = reader.identityOf(file);
        assumeTrue(identity.isPresent(), "native file identity isn't available on this machine");
        Files.delete(file);

        assertEquals(NativeFileIdReader.Location.Kind.GONE, reader.locate(identity.get(), tempDir).kind(),
                "after deletion the old identity must be GONE, not unknown");
    }
}
