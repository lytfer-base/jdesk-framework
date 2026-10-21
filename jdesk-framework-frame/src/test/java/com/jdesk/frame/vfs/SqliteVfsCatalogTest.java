package com.jdesk.frame.vfs;

import com.jdesk.core.app.PathManager;
import com.jdesk.core.disposer.Disposer;
import com.jdesk.core.vfs.ScanFilter;
import com.jdesk.core.vfs.VfsFileId;
import com.jdesk.core.vfs.VfsFileStatus;
import com.jdesk.core.vfs.VfsManagedFile;
import com.jdesk.core.vfs.VfsRoot;
import com.jdesk.core.vfs.VfsRootId;
import com.jdesk.core.vfs.VfsScanResult;
import com.jdesk.frame.storage.DatabaseDescriptor;
import com.jdesk.frame.storage.DatabaseHandle;
import com.jdesk.frame.storage.DatabaseOwner;
import com.jdesk.frame.storage.DatabaseService;
import com.jdesk.frame.storage.StorageException;
import com.jdesk.frame.storage.sqlite.SqliteDatabaseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.UserPrincipal;
import java.text.Collator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SqliteVfsCatalogTest {

    @TempDir
    Path homeDir;

    @AfterEach
    void clearState() {
        Disposer.clearAll();
        PathManager.resetForTests();
    }

    @Test
    void registerRootIsIdempotentForTheSameKey() {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.roots");

        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path rootPath = homeDir.resolve("library");

            VfsRootId first = catalog.registerRoot("library", "Library", rootPath);
            VfsRootId second = catalog.registerRoot("library", "Library Again", rootPath.toAbsolutePath());

            List<VfsRoot> roots = catalog.listRoots();
            assertEquals(first, second);
            assertEquals(1, roots.size());
            assertEquals(rootPath.toAbsolutePath().normalize(), roots.get(0).rootPath());
        } finally {
            Disposer.dispose(database);
        }
    }

    /**
     * A path belongs to one root: registering it under another key must fail with a clear message, not a bare
     * {@code unique(root_path)} constraint error. Rows with an empty {@code root_key} take this path too.
     */
    @Test
    void registerRootRejectsASecondKeyOnAnAlreadyRegisteredPath() {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.roots.conflict");

        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path rootPath = homeDir.resolve("library");
            catalog.registerRoot("library", "Library", rootPath);

            StorageException rejected = assertThrows(StorageException.class,
                    () -> catalog.registerRoot("other-key", "Library", rootPath));

            String message = rejected.getMessage();
            assertTrue(message.contains("library"), "must name the key that owns the path: " + message);
            assertTrue(message.contains("other-key"), "must name the requested key: " + message);
            assertEquals(1, catalog.listRoots().size(), "a conflict must not leave a second root");
        } finally {
            Disposer.dispose(database);
        }
    }

    @Test
    void registerFileReturnsStableFileIdForRootAndRelativePath() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.files");

        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path rootPath = homeDir.resolve("library");
            Files.createDirectories(rootPath.resolve("folder"));
            Files.writeString(rootPath.resolve("folder").resolve("note.md"), "hello");
            VfsRootId rootId = catalog.registerRoot("Library", "Library", rootPath);

            VfsManagedFile first = catalog.registerFile(rootId, "folder\\note.md", false);
            VfsManagedFile second = catalog.registerFile(rootId, "folder/note.md", false);

            assertEquals(first.fileId(), second.fileId());
            assertEquals("folder/note.md", first.relativePath());
            assertEquals(VfsFileStatus.PRESENT, first.status());
            assertTrue(first.size() > 0);
            assertEquals(first.fileId(), catalog.findFile(rootId, "folder/note.md").orElseThrow().fileId());
            assertEquals(first.relativePath(), catalog.findFile(first.fileId()).orElseThrow().relativePath());
        } finally {
            Disposer.dispose(database);
        }
    }

    @Test
    void moveFilePreservesIdAndChangesCatalogPath() {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.moves");

        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path rootPath = homeDir.resolve("library");
            VfsRootId rootId = catalog.registerRoot("Library", "Library", rootPath);
            VfsManagedFile file = catalog.registerFile(rootId, "folder/note.md", false);

            VfsManagedFile moved = catalog.moveFile(file.fileId(), rootId, "folder/renamed.md");

            assertEquals(file.fileId(), moved.fileId());
            assertEquals("folder/renamed.md", moved.relativePath());
            assertFalse(catalog.findFile(rootId, "folder/note.md").isPresent());
            assertEquals("folder/renamed.md", catalog.findFile(file.fileId()).orElseThrow().relativePath());
        } finally {
            Disposer.dispose(database);
        }
    }

    /**
     * Moving onto the path of a file deleted outside: the MISSING row gives the path up to the moved identity, and
     * neither the old identity nor its read-only flag carries over, even after reconcile.
     */
    @Test
    void moveFileOntoThePathOfAFileDeletedOutsideKeepsItsOwnIdentity() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.move.onto-missing");
        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path rootPath = Files.createDirectories(homeDir.resolve("library"));
            Files.writeString(rootPath.resolve("draft.md"), "draft");
            Files.writeString(rootPath.resolve("plan.md"), "plan");
            VfsRootId rootId = catalog.registerRoot("library", "Library", rootPath);
            catalog.scanRoot(rootId, ScanFilter.all());
            VfsFileId draft = catalog.findFile(rootId, "draft.md").orElseThrow().fileId();
            VfsFileId deleted = catalog.findFile(rootId, "plan.md").orElseThrow().fileId();
            catalog.setReadOnly(deleted, true);
            Files.delete(rootPath.resolve("plan.md"));
            catalog.refresh(rootId, "", false, ScanFilter.all());
            assertEquals(VfsFileStatus.MISSING, catalog.findFile(deleted).orElseThrow().status(), "precondition: a MISSING row");

            Files.move(rootPath.resolve("draft.md"), rootPath.resolve("plan.md"));
            VfsManagedFile moved = catalog.moveFile(draft, rootId, "plan.md");
            catalog.refresh(rootId, "", false, ScanFilter.all());

            VfsManagedFile atPath = catalog.findFile(rootId, "plan.md").orElseThrow();
            assertEquals(draft, moved.fileId());
            assertEquals(draft, atPath.fileId(), "after reconcile the path still holds the moved identity");
            assertEquals(VfsFileStatus.PRESENT, atPath.status());
            assertFalse(atPath.readOnly(), "the old identity's read-only flag doesn't carry over");
            assertTrue(catalog.findFile(deleted).isEmpty(), "the MISSING row that gave up the path is deleted");
        } finally {
            Disposer.dispose(database);
        }
    }

    /** Moving onto a path held by a present file is refused, and both rows stay as they were. */
    @Test
    void moveFileRefusesAPathHeldByAPresentFile() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.move.onto-present");
        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path rootPath = Files.createDirectories(homeDir.resolve("library"));
            Files.writeString(rootPath.resolve("draft.md"), "draft");
            Files.writeString(rootPath.resolve("plan.md"), "plan");
            VfsRootId rootId = catalog.registerRoot("library", "Library", rootPath);
            catalog.scanRoot(rootId, ScanFilter.all());
            VfsFileId draft = catalog.findFile(rootId, "draft.md").orElseThrow().fileId();
            VfsFileId plan = catalog.findFile(rootId, "plan.md").orElseThrow().fileId();

            StorageException refused = assertThrows(StorageException.class,
                    () -> catalog.moveFile(draft, rootId, "plan.md"));

            assertTrue(refused.getMessage().contains("plan.md"), "must name the taken path: " + refused.getMessage());
            assertEquals("draft.md", catalog.findFile(draft).orElseThrow().relativePath());
            assertEquals(plan, catalog.findFile(rootId, "plan.md").orElseThrow().fileId());
        } finally {
            Disposer.dispose(database);
        }
    }

    @Test
    void updateStatusKeepsIdAndIncrementsContentVersion() {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.status");

        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            VfsRootId rootId = catalog.registerRoot("Library", "Library", homeDir.resolve("library"));
            VfsManagedFile file = catalog.registerFile(rootId, "note.md", false);
            VfsFileId fileId = file.fileId();

            VfsManagedFile missing = catalog.updateStatus(fileId, VfsFileStatus.MISSING);

            assertEquals(fileId, missing.fileId());
            assertEquals(VfsFileStatus.MISSING, missing.status());
            assertEquals(file.contentVersion() + 1, missing.contentVersion());
            assertEquals(VfsFileStatus.MISSING, catalog.findFile(fileId).orElseThrow().status());
        } finally {
            Disposer.dispose(database);
        }
    }

    @Test
    void registerFileRecordsParentPathColumn() {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.parentpath");

        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            VfsRootId rootId = catalog.registerRoot("Library", "Library", homeDir.resolve("library"));
            catalog.registerFile(rootId, "folder", true);
            catalog.registerFile(rootId, "folder/note.md", false);
            catalog.registerFile(rootId, "top.md", false);

            List<String> parents = database.read(session -> session.queryList("""
                    select parent_path from jdesk_vfs_files
                    order by relative_path
                    """, row -> row.getString("parent_path")));

            // Ordered by relative_path: folder, folder/note.md, top.md
            assertEquals(List.of("", "folder", ""), parents);
        } finally {
            Disposer.dispose(database);
        }
    }

    @Test
    void listChildrenReturnsDirectChildrenDirectoriesFirst() {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.list");

        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            VfsRootId rootId = catalog.registerRoot("Library", "Library", homeDir.resolve("library"));
            catalog.registerFile(rootId, "Beta.md", false);
            catalog.registerFile(rootId, "alpha", true);
            catalog.registerFile(rootId, "alpha/inner.md", false);
            catalog.registerFile(rootId, "Zeta.md", false);

            List<VfsManagedFile> top = catalog.listChildren(rootId, "");
            List<String> topNames = top.stream().map(VfsManagedFile::relativePath).toList();
            // Directories first (alpha), then names case-insensitively (Beta, Zeta).
            assertEquals(List.of("alpha", "Beta.md", "Zeta.md"), topNames);
            assertTrue(top.get(0).directory());

            List<VfsManagedFile> underAlpha = catalog.listChildren(rootId, "alpha");
            assertEquals(List.of("alpha/inner.md"),
                    underAlpha.stream().map(VfsManagedFile::relativePath).toList());

            assertTrue(catalog.listChildren(rootId, "alpha/inner.md").isEmpty());
        } finally {
            Disposer.dispose(database);
        }
    }

    @Test
    void findFileByAbsolutePathResolvesViaRootPrefix() {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.bypath");

        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path rootPath = homeDir.resolve("library");
            VfsRootId rootId = catalog.registerRoot("Library", "Library", rootPath);
            VfsManagedFile file = catalog.registerFile(rootId, "folder/note.md", false);

            VfsManagedFile hit = catalog.findFile(rootPath.resolve("folder").resolve("note.md")).orElseThrow();
            assertEquals(file.fileId(), hit.fileId());

            assertTrue(catalog.findFile(rootPath).isEmpty());                       // The root isn't a managed file.
            assertTrue(catalog.findFile(homeDir.resolve("outside.md")).isEmpty());  // Outside the root.
        } finally {
            Disposer.dispose(database);
        }
    }

    @Test
    void scanRootPopulatesSnapshotAndIsIdempotent() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.scan");

        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path rootPath = homeDir.resolve("library");
            Files.createDirectories(rootPath.resolve("docs"));
            Files.writeString(rootPath.resolve("docs").resolve("a.md"), "alpha");
            Files.writeString(rootPath.resolve("top.md"), "top");
            VfsRootId rootId = catalog.registerRoot("Library", "Library", rootPath);

            VfsScanResult first = catalog.scanRoot(rootId, ScanFilter.all());
            // docs(dir) + docs/a.md + top.md = 3 added
            assertEquals(3, first.added().size());
            assertEquals(0, first.missing().size());

            VfsManagedFile aMd = catalog.findFile(rootId, "docs/a.md").orElseThrow();
            assertEquals(VfsFileStatus.PRESENT, aMd.status());

            VfsScanResult second = catalog.scanRoot(rootId, ScanFilter.all());
            assertEquals(0, second.added().size());
            assertEquals(0, second.updated().size());
            assertEquals(0, second.missing().size());
            assertEquals(3, second.unchanged());
            // The id and content_version stay the same.
            assertEquals(aMd.fileId(), catalog.findFile(rootId, "docs/a.md").orElseThrow().fileId());
            assertEquals(aMd.contentVersion(), catalog.findFile(rootId, "docs/a.md").orElseThrow().contentVersion());
        } finally {
            Disposer.dispose(database);
        }
    }

    @Test
    void scanDetectsAddedUpdatedAndMissing() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.scan.diff");

        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path rootPath = homeDir.resolve("library");
            Files.createDirectories(rootPath);
            Path keep = rootPath.resolve("keep.md");
            Path gone = rootPath.resolve("gone.md");
            Files.writeString(keep, "v1");
            Files.writeString(gone, "bye");
            VfsRootId rootId = catalog.registerRoot("Library", "Library", rootPath);
            catalog.scanRoot(rootId, ScanFilter.all());

            // Change keep (its size changes), delete gone, add fresh.
            Files.writeString(keep, "v1-longer-content");
            Files.delete(gone);
            Files.writeString(rootPath.resolve("fresh.md"), "new");

            VfsScanResult result = catalog.scanRoot(rootId, ScanFilter.all());

            assertEquals(List.of("fresh.md"),
                    result.added().stream().map(VfsManagedFile::relativePath).toList());
            assertEquals(List.of("keep.md"),
                    result.updated().stream().map(VfsManagedFile::relativePath).toList());
            assertEquals(List.of("gone.md"),
                    result.missing().stream().map(VfsManagedFile::relativePath).toList());

            VfsManagedFile keepRow = catalog.findFile(rootId, "keep.md").orElseThrow();
            assertEquals(2, keepRow.contentVersion()); // 1 at first, +1 when updated.
            assertEquals(VfsFileStatus.MISSING, catalog.findFile(rootId, "gone.md").orElseThrow().status());
        } finally {
            Disposer.dispose(database);
        }
    }

    @Test
    void refreshSubtreeDoesNotTouchSiblings() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.scan.subtree");

        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path rootPath = homeDir.resolve("library");
            Files.createDirectories(rootPath.resolve("a"));
            Files.createDirectories(rootPath.resolve("b"));
            Files.writeString(rootPath.resolve("a").resolve("x.md"), "x");
            Files.writeString(rootPath.resolve("b").resolve("y.md"), "y");
            VfsRootId rootId = catalog.registerRoot("Library", "Library", rootPath);
            catalog.scanRoot(rootId, ScanFilter.all());

            // Delete b/y.md but refresh only subtree a, recursively.
            Files.delete(rootPath.resolve("b").resolve("y.md"));
            VfsScanResult result = catalog.refresh(rootId, "a", true, ScanFilter.all());

            assertEquals(0, result.missing().size()); // Subtree b is untouched.
            assertEquals(VfsFileStatus.PRESENT, catalog.findFile(rootId, "b/y.md").orElseThrow().status());

            // Only refreshing subtree b marks it.
            VfsScanResult bResult = catalog.refresh(rootId, "b", true, ScanFilter.all());
            assertEquals(List.of("b/y.md"),
                    bResult.missing().stream().map(VfsManagedFile::relativePath).toList());
        } finally {
            Disposer.dispose(database);
        }
    }

    /**
     * A refresh interrupted on the writer thread, as when the database closes, rolls back wholly, recursive or not;
     * the interrupt doesn't linger and the next refresh runs normally.
     */
    @Test
    void aRefreshInterruptedOnTheWriterThreadRollsBackWholly() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.refresh.interrupted");
        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path rootPath = Files.createDirectories(homeDir.resolve("library"));
            Files.writeString(rootPath.resolve("old.md"), "old");
            VfsRootId rootId = catalog.registerRoot("library", "Library", rootPath);
            catalog.scanRoot(rootId, ScanFilter.all());
            Files.delete(rootPath.resolve("old.md"));
            for (int i = 0; i < 6; i++) {
                Files.writeString(rootPath.resolve("new-" + i + ".md"), "new");
            }
            int[] filesSeen = {0};
            ScanFilter interruptAtTheThirdFile = (path, directory) -> {
                if (!directory && ++filesSeen[0] == 3) {
                    Thread.currentThread().interrupt(); // Interrupts the writer thread as a close would.
                }
                return true;
            };

            for (boolean recursive : new boolean[] {true, false}) {
                filesSeen[0] = 0;
                assertThrows(CancellationException.class,
                        () -> catalog.refresh(rootId, "", recursive, interruptAtTheThirdFile), "recursive=" + recursive);
                assertEquals(List.of("old.md"),
                        catalog.listAll(rootId).stream().map(VfsManagedFile::relativePath).toList(),
                        "no new rows from this pass may be stored: recursive=" + recursive);
                assertEquals(VfsFileStatus.PRESENT, catalog.findFile(rootId, "old.md").orElseThrow().status(),
                        "no missing marks from this pass may be stored either: recursive=" + recursive);
            }

            VfsScanResult again = catalog.refresh(rootId, "", true, ScanFilter.all());
            assertEquals(6, again.added().size(), "the interrupt must not linger; the next pass runs normally");
            assertEquals(List.of("old.md"), again.missing().stream().map(VfsManagedFile::relativePath).toList());
        } finally {
            Thread.interrupted();
            Disposer.dispose(database);
        }
    }

    @Test
    void scanFilterExcludesDirectorySubtree() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.scan.filter");

        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path rootPath = homeDir.resolve("library");
            Files.createDirectories(rootPath.resolve(".myapp-local").resolve("thumbs"));
            Files.writeString(rootPath.resolve(".myapp-local").resolve("thumbs").resolve("t.bin"), "x");
            Files.writeString(rootPath.resolve("keep.md"), "k");
            VfsRootId rootId = catalog.registerRoot("Library", "Library", rootPath);

            ScanFilter skipLocal = (path, directory) -> !path.getFileName().toString().equals(".myapp-local");
            VfsScanResult result = catalog.scanRoot(rootId, skipLocal);

            assertEquals(List.of("keep.md"),
                    result.added().stream().map(VfsManagedFile::relativePath).toList());
            assertTrue(catalog.findFile(rootId, ".myapp-local").isEmpty());
            assertTrue(catalog.findFile(rootId, ".myapp-local/thumbs/t.bin").isEmpty());
        } finally {
            Disposer.dispose(database);
        }
    }

    @Test
    void scanRecordsFileNameSortKeyAndCreationTime() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.columns");
        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database, Locale.CHINA);
            Path rootPath = homeDir.resolve("library");
            Files.createDirectories(rootPath.resolve("docs"));
            Files.writeString(rootPath.resolve("docs").resolve("阿里.md"), "a");
            Files.writeString(rootPath.resolve("中国.md"), "z");
            VfsRootId rootId = catalog.registerRoot("Library", "Library", rootPath);
            catalog.scanRoot(rootId, ScanFilter.all());

            VfsManagedFile ali = catalog.findFile(rootId, "docs/阿里.md").orElseThrow();
            long fsCreated = Files.readAttributes(rootPath.resolve("docs").resolve("阿里.md"),
                    BasicFileAttributes.class).creationTime().toMillis();
            assertEquals(fsCreated, ali.fileCreatedAt());

            // file_name is the last path segment, not the relative path; hidden checks and sorting use it.
            assertEquals("阿里.md", readColumnText(database, ali.fileId(), "file_name"));

            // The sort key is stored and follows pinyin order: ali sorts before zhongguo.
            VfsManagedFile zhong = catalog.findFile(rootId, "中国.md").orElseThrow();
            assertTrue(Arrays.compareUnsigned(
                    readColumnBytes(database, ali.fileId(), "file_name_sort"),
                    readColumnBytes(database, zhong.fileId(), "file_name_sort")) < 0);
        } finally {
            Disposer.dispose(database);
        }
    }

    @Test
    void renameRewritesFileNameAndSortKeyButKeepsCreationTime() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.rename.columns");
        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database, Locale.CHINA);
            Path rootPath = homeDir.resolve("library");
            Files.createDirectories(rootPath);
            Files.writeString(rootPath.resolve("old.md"), "v1");
            VfsRootId rootId = catalog.registerRoot("Library", "Library", rootPath);
            catalog.scanRoot(rootId, ScanFilter.all());

            VfsManagedFile before = catalog.findFile(rootId, "old.md").orElseThrow();
            long createdBefore = before.fileCreatedAt();
            Path newPath = rootPath.resolve("new.md");
            Files.move(rootPath.resolve("old.md"), newPath);
            // NTFS keeps creationTime on rename; spoof it so the last assertion fails if moveFile rewrites the column.
            FileTime spoofedCreation = FileTime.fromMillis(createdBefore - Duration.ofDays(1).toMillis());
            Files.setAttribute(newPath, "basic:creationTime", spoofedCreation);
            assertEquals(spoofedCreation.toMillis(),
                    Files.readAttributes(newPath, BasicFileAttributes.class).creationTime().toMillis(),
                    "precondition: the file system must accept the change, or the last assertion is vacuous");
            catalog.moveFile(before.fileId(), rootId, "new.md");

            assertEquals("new.md", readColumnText(database, before.fileId(), "file_name"));
            Collator collator = VfsFileNameSort.collatorFor(Locale.CHINA);
            assertEquals(0, Arrays.compareUnsigned(
                    VfsFileNameSort.key(collator, "new.md"),
                    readColumnBytes(database, before.fileId(), "file_name_sort")));
            // A move or rename keeps the creation time, even though the new path now has another creationTime.
            assertEquals(createdBefore, catalog.findFile(before.fileId()).orElseThrow().fileCreatedAt());
        } finally {
            Disposer.dispose(database);
        }
    }

    /** {@code listAll} returns the whole tree in one query, directories and MISSING rows included. */
    @Test
    void listAllReturnsTheWholeTreeInOneQuery() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.listall");
        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path rootPath = homeDir.resolve("library");
            Files.createDirectories(rootPath.resolve("docs").resolve("sub"));
            Files.writeString(rootPath.resolve("top.md"), "t");
            Files.writeString(rootPath.resolve("docs").resolve("a.md"), "a");
            Files.writeString(rootPath.resolve("docs").resolve("sub").resolve("b.md"), "b");
            VfsRootId rootId = catalog.registerRoot("library", "Library", rootPath);
            catalog.scanRoot(rootId, ScanFilter.all());

            List<String> all = catalog.listAll(rootId).stream()
                    .map(VfsManagedFile::relativePath).sorted().toList();

            assertEquals(List.of("docs", "docs/a.md", "docs/sub", "docs/sub/b.md", "top.md"), all);
        } finally {
            Disposer.dispose(database);
        }
    }

    private static String readColumnText(DatabaseHandle database, VfsFileId fileId, String column) {
        return database.read(session -> session.queryOne(
                "select " + column + " as v from jdesk_vfs_files where file_id = ?",
                row -> row.getString("v"), fileId.value())).orElseThrow();
    }

    private static byte[] readColumnBytes(DatabaseHandle database, VfsFileId fileId, String column) {
        return database.read(session -> session.queryOne(
                "select " + column + " as v from jdesk_vfs_files where file_id = ?",
                row -> row.getBytes("v"), fileId.value())).orElseThrow();
    }

    /**
     * An incomplete scan marks nothing MISSING: when a directory stream breaks, entries not reached look exactly like
     * deleted ones. Real I/O failures can't be produced in a unit test, so the decision is tested as a pure function.
     */
    @Test
    void detectMissingReportsNothingWhenScanIncomplete() {
        List<VfsManagedFile> snapshot = List.of(
                snapshotRow("a.md", VfsFileStatus.PRESENT),
                snapshotRow("b.md", VfsFileStatus.PRESENT));

        assertTrue(SqliteVfsCatalog.detectMissing(snapshot, Set.of(), false, Set.of()).isEmpty(),
                "judging absence after an incomplete scan treats unvisited files as gone");
        assertEquals(List.of("a.md", "b.md"),
                SqliteVfsCatalog.detectMissing(snapshot, Set.of(), true, Set.of()).stream()
                        .map(VfsManagedFile::relativePath).toList(),
                "only after a complete scan does absence mean gone");
    }

    /**
     * A seen entry whose attributes can't be read isn't MISSING; an antivirus scan or an exclusive lock can cause
     * that. {@code walkEntries} collects such entries in {@code unreadable} and adds them to the seen set.
     */
    @Test
    void detectMissingSkipsEntriesThatWereSeenButUnreadable() {
        List<VfsManagedFile> snapshot = List.of(
                snapshotRow("locked.md", VfsFileStatus.PRESENT),
                snapshotRow("gone.md", VfsFileStatus.PRESENT));

        assertEquals(List.of("gone.md"),
                SqliteVfsCatalog.detectMissing(snapshot, Set.of("locked.md"), true, Set.of()).stream()
                        .map(VfsManagedFile::relativePath).toList());
    }

    /** Rows already MISSING aren't marked again, which would rewrite them and repeat them in the diff each pass. */
    @Test
    void detectMissingIgnoresRowsAlreadyMissing() {
        List<VfsManagedFile> snapshot = List.of(snapshotRow("gone.md", VfsFileStatus.MISSING));
        assertTrue(SqliteVfsCatalog.detectMissing(snapshot, Set.of(), true, Set.of()).isEmpty());
    }

    /** Rows under a subtree not fully walked aren't missing; if the start itself wasn't fully walked, nothing is. */
    @Test
    void detectMissingSkipsRowsUnderSubtreesThatWereNotFullyWalked() {
        List<VfsManagedFile> snapshot = List.of(
                snapshotRow("locked/a.md", VfsFileStatus.PRESENT),
                snapshotRow("locked/sub/b.md", VfsFileStatus.PRESENT),
                snapshotRow("gone.md", VfsFileStatus.PRESENT));

        assertEquals(List.of("gone.md"),
                SqliteVfsCatalog.detectMissing(snapshot, Set.of("locked"), true, Set.of("locked")).stream()
                        .map(VfsManagedFile::relativePath).toList());
        assertTrue(SqliteVfsCatalog.detectMissing(snapshot, Set.of(), true, Set.of("")).isEmpty());
    }

    /**
     * A full reconcile keeps the rows under a directory it can't open. An ACL that denies listing produces a real
     * {@code visitFileFailed}; skipped where the DENY doesn't take effect.
     */
    @Test
    void fullReconcileKeepsRowsUnderADirectoryThatCannotBeOpened() throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"), "needs an NTFS ACL");
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.unopenable");
        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path rootPath = homeDir.resolve("library");
            Path locked = rootPath.resolve("locked");
            Files.createDirectories(locked.resolve("sub"));
            Files.writeString(locked.resolve("a.md"), "a");
            Files.writeString(locked.resolve("sub").resolve("b.md"), "b");
            Files.writeString(rootPath.resolve("ok.md"), "ok");
            VfsRootId rootId = catalog.registerRoot("library", "Library", rootPath);
            catalog.scanRoot(rootId, ScanFilter.all());

            AclFileAttributeView acl = Files.getFileAttributeView(locked, AclFileAttributeView.class);
            List<AclEntry> original = acl.getAcl();
            UserPrincipal me = locked.getFileSystem().getUserPrincipalLookupService()
                    .lookupPrincipalByName(System.getProperty("user.name"));
            List<AclEntry> denied = new ArrayList<>();
            denied.add(AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(me)
                    .setPermissions(AclEntryPermission.LIST_DIRECTORY).build());
            denied.addAll(original);
            acl.setAcl(denied);
            try {
                assumeTrue(cannotList(locked), "the DENY didn't take effect on this machine");
                catalog.refresh(rootId, "", true, ScanFilter.all());
            } finally {
                acl.setAcl(original);
            }

            for (String path : List.of("locked", "locked/a.md", "locked/sub", "locked/sub/b.md", "ok.md")) {
                assertEquals(VfsFileStatus.PRESENT, catalog.findFile(rootId, path).orElseThrow().status(),
                        path + " is still on disk; it just couldn't be read this pass");
            }
        } finally {
            Disposer.dispose(database);
        }
    }

    private static boolean cannotList(Path directory) throws IOException {
        try (var ignored = Files.newDirectoryStream(directory)) {
            return false;
        } catch (AccessDeniedException denied) {
            return true;
        }
    }

    /** Builds a snapshot row with placeholder values in the fields {@code detectMissing} ignores. */
    private static VfsManagedFile snapshotRow(String relativePath, VfsFileStatus status) {
        return new VfsManagedFile(VfsFileId.of("id-" + relativePath), VfsRootId.of("root-1"),
                relativePath, Path.of("C:/x").resolve(relativePath), status, false, 0L, 0L, 0L, 1L);
    }

    /** A file revived from MISSING is reported as added, not updated, so subscribers see a new item. */
    @Test
    void refreshReportsFileRevivedFromMissing() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.revive");

        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path rootPath = homeDir.resolve("library");
            Files.createDirectories(rootPath);
            Files.writeString(rootPath.resolve("note.md"), "still here");
            VfsRootId rootId = catalog.registerRoot("Library", "Library", rootPath);
            catalog.scanRoot(rootId, ScanFilter.all());
            VfsManagedFile before = catalog.findFile(rootId, "note.md").orElseThrow();
            catalog.updateStatus(before.fileId(), VfsFileStatus.MISSING);

            VfsScanResult result = catalog.refresh(rootId, "", false, ScanFilter.all());

            assertEquals(List.of("note.md"), result.added().stream().map(VfsManagedFile::relativePath).toList(),
                    "a revived file must be in added");
            assertTrue(result.updated().isEmpty(), "a revival isn't a content update, got: " + result.updated());
            assertEquals(before.fileId(), result.added().get(0).fileId(), "a revival in place keeps the file_id");
            assertEquals(VfsFileStatus.PRESENT,
                    catalog.findFile(rootId, "note.md").orElseThrow().status());
        } finally {
            Disposer.dispose(database);
        }
    }

    /** A file deleted and recreated at the same path with new content is added, and its version is bumped. */
    @Test
    void refreshReportsRevivalWithNewContentAsAdded() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.revive.changed");

        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path rootPath = homeDir.resolve("library");
            Files.createDirectories(rootPath);
            Path note = rootPath.resolve("note.md");
            Files.writeString(note, "old");
            VfsRootId rootId = catalog.registerRoot("Library", "Library", rootPath);
            catalog.scanRoot(rootId, ScanFilter.all());
            Files.delete(note);
            long missingVersion = catalog.refresh(rootId, "", false, ScanFilter.all())
                    .missing().get(0).contentVersion();
            Files.writeString(note, "a different, longer body");

            VfsScanResult result = catalog.refresh(rootId, "", false, ScanFilter.all());

            assertEquals(List.of("note.md"), result.added().stream().map(VfsManagedFile::relativePath).toList());
            assertTrue(result.updated().isEmpty(), "got: " + result.updated());
            assertTrue(result.added().get(0).contentVersion() > missingVersion, "new content must bump the version");
        } finally {
            Disposer.dispose(database);
        }
    }

    /**
     * A revival does <b>not</b> bump {@code content_version}: the content is unchanged, and a bump would invalidate
     * caches keyed on the version.
     */
    @Test
    void refreshDoesNotBumpVersionWhenOnlyReviving() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.revive.version");

        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path rootPath = homeDir.resolve("library");
            Files.createDirectories(rootPath);
            Files.writeString(rootPath.resolve("note.md"), "still here");
            VfsRootId rootId = catalog.registerRoot("Library", "Library", rootPath);
            catalog.scanRoot(rootId, ScanFilter.all());
            VfsManagedFile before = catalog.findFile(rootId, "note.md").orElseThrow();
            // updateStatus bumps once itself because the status changed; use its result as the baseline.
            long baseline = catalog.updateStatus(before.fileId(), VfsFileStatus.MISSING).contentVersion();

            catalog.refresh(rootId, "", false, ScanFilter.all());

            assertEquals(baseline, catalog.findFile(rootId, "note.md").orElseThrow().contentVersion());
        } finally {
            Disposer.dispose(database);
        }
    }

    /**
     * Writer self-registration: {@code refreshFile} rereads the file's attributes into the snapshot and bumps
     * {@code content_version} when the content changed, so the app's own writes show at once, not after the watcher's
     * debounce.
     */
    @Test
    void refreshFileWritesBackSizeAndBumpsVersionWhenContentChanged() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.refreshfile");

        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path rootPath = homeDir.resolve("library");
            Files.createDirectories(rootPath);
            Path file = rootPath.resolve("note.md");
            Files.writeString(file, "old");
            VfsRootId rootId = catalog.registerRoot("Library", "Library", rootPath);
            catalog.scanRoot(rootId, ScanFilter.all());
            VfsManagedFile before = catalog.findFile(rootId, "note.md").orElseThrow();

            Files.writeString(file, "much longer content");
            // Push mtime forward: two writes in the same millisecond may keep it, leaving only the size to differ.
            Files.setLastModifiedTime(file, FileTime.fromMillis(before.modifiedAt() + 5_000));

            VfsManagedFile after = catalog.refreshFile(before.fileId());

            assertEquals(before.fileId(), after.fileId(), "the identity stays, since metadata hangs off the file_id");
            assertEquals(Files.size(file), after.size());
            assertEquals(before.modifiedAt() + 5_000, after.modifiedAt());
            assertEquals(before.contentVersion() + 1, after.contentVersion(),
                    "changed content must bump the version, or version-keyed caches never invalidate");
            assertEquals(VfsFileStatus.PRESENT, after.status());
        } finally {
            Disposer.dispose(database);
        }
    }

    /** Unchanged content must <b>not</b> bump the version, or version-keyed caches would miss on every save. */
    @Test
    void refreshFileKeepsVersionWhenNothingChanged() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.refreshfile.noop");

        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path rootPath = homeDir.resolve("library");
            Files.createDirectories(rootPath);
            Files.writeString(rootPath.resolve("note.md"), "same");
            VfsRootId rootId = catalog.registerRoot("Library", "Library", rootPath);
            catalog.scanRoot(rootId, ScanFilter.all());
            VfsManagedFile before = catalog.findFile(rootId, "note.md").orElseThrow();

            VfsManagedFile after = catalog.refreshFile(before.fileId());

            assertEquals(before.contentVersion(), after.contentVersion());
        } finally {
            Disposer.dispose(database);
        }
    }

    /** A file gone from disk is marked MISSING, as a full reconcile would, instead of throwing or staying PRESENT. */
    @Test
    void refreshFileMarksMissingWhenFileGone() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.refreshfile.gone");

        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path rootPath = homeDir.resolve("library");
            Files.createDirectories(rootPath);
            Path file = rootPath.resolve("note.md");
            Files.writeString(file, "bye");
            VfsRootId rootId = catalog.registerRoot("Library", "Library", rootPath);
            catalog.scanRoot(rootId, ScanFilter.all());
            VfsManagedFile before = catalog.findFile(rootId, "note.md").orElseThrow();

            Files.delete(file);
            VfsManagedFile after = catalog.refreshFile(before.fileId());

            assertEquals(VfsFileStatus.MISSING, after.status());
        } finally {
            Disposer.dispose(database);
        }
    }

    @Test
    void registerRootByKeyReusesRootIdAfterTheDirectoryMoves() {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.roots.moved");

        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path before = homeDir.resolve("before");
            Path after = homeDir.resolve("after");

            VfsRootId first = catalog.registerRoot("project-root", "Project", before);
            VfsRootId second = catalog.registerRoot("project-root", "Project", after);

            assertEquals(first, second, "a moved root directory must keep its root id");
            List<VfsRoot> roots = catalog.listRoots();
            assertEquals(1, roots.size(), "a move must not leave a second root");
            assertEquals(after.toAbsolutePath().normalize(), roots.get(0).rootPath());
        } finally {
            Disposer.dispose(database);
        }
    }

    @Test
    void movingTheRootKeepsFileIdsAndResolvesAbsolutePathsUnderTheNewLocation() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.roots.relocate");

        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path before = homeDir.resolve("before");
            Files.createDirectories(before.resolve("sub"));
            Files.writeString(before.resolve("note.md"), "hi");
            Files.writeString(before.resolve("sub").resolve("deep.md"), "deep");

            VfsRootId rootId = catalog.registerRoot("project-root", "Project", before);
            catalog.scanRoot(rootId, ScanFilter.all());
            VfsFileId noteId = catalog.findFile(rootId, "note.md").orElseThrow().fileId();
            VfsFileId deepId = catalog.findFile(rootId, "sub/deep.md").orElseThrow().fileId();

            // Move the whole root directory, as a drive change, backup restore or copy elsewhere would.
            Path after = homeDir.resolve("after");
            Files.move(before, after);
            VfsRootId sameRoot = catalog.registerRoot("project-root", "Project", after);

            assertEquals(rootId, sameRoot);
            // The file_id must stay, or metadata keyed on it is orphaned.
            assertEquals(noteId, catalog.findFile(sameRoot, "note.md").orElseThrow().fileId());
            assertEquals(deepId, catalog.findFile(sameRoot, "sub/deep.md").orElseThrow().fileId());
            // Absolute paths must point to the new location, or reads and writes hit the old path.
            assertEquals(after.resolve("note.md"),
                    catalog.findFile(noteId).orElseThrow().absolutePath());
            assertEquals(after.resolve("sub").resolve("deep.md"),
                    catalog.findFile(deepId).orElseThrow().absolutePath());
        } finally {
            Disposer.dispose(database);
        }
    }

    @Test
    void scanStoresNativeIdentityForEachEntry() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.nativeid.scan");

        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path rootPath = homeDir.resolve("library");
            Files.createDirectories(rootPath.resolve("docs"));
            Path note = rootPath.resolve("docs").resolve("note.md");
            Files.writeString(note, "hi");

            Optional<String> live = NativeFileIdReaders.forCurrentPlatform().identityOf(note);
            assumeTrue(live.isPresent(), "native file identity isn't available on this machine");

            VfsRootId rootId = catalog.registerRoot("project-root", "Library", rootPath);
            catalog.scanRoot(rootId, ScanFilter.all());

            assertEquals(live.get(), storedNativeId(database, "docs/note.md"),
                    "the scan must store the file's native identity");
            assertFalse(storedNativeId(database, "docs").isBlank(),
                    "directories need it too, so their renames are recognized");
        } finally {
            Disposer.dispose(database);
        }
    }

    @Test
    void rescanRefreshesNativeIdentityAfterAFileIsReplacedInPlace() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.nativeid.replaced");

        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path rootPath = homeDir.resolve("library");
            Files.createDirectories(rootPath);
            Path note = rootPath.resolve("note.md");
            Files.writeString(note, "v1");
            assumeTrue(NativeFileIdReaders.forCurrentPlatform().identityOf(note).isPresent(),
                    "native file identity isn't available on this machine");

            VfsRootId rootId = catalog.registerRoot("project-root", "Library", rootPath);
            catalog.scanRoot(rootId, ScanFilter.all());
            String first = storedNativeId(database, "note.md");

            // Replace via a temp file, as editors and git do: the path still matches, so the scan must refresh the id.
            Path tmp = rootPath.resolve("note.md.tmp");
            Files.writeString(tmp, "v2-after-replacement");
            Files.move(tmp, note, StandardCopyOption.REPLACE_EXISTING);
            catalog.scanRoot(rootId, ScanFilter.all());

            String second = storedNativeId(database, "note.md");
            assertNotEquals(first, second, "after a replacement the snapshot's native identity must change too");
            assertEquals(NativeFileIdReaders.forCurrentPlatform().identityOf(note).orElseThrow(), second,
                    "the refreshed value should be the identity of the file now on disk");
        } finally {
            Disposer.dispose(database);
        }
    }

    @Test
    void rescanRecognisesAnExternallyRenamedFileAndKeepsItsFileId() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.rename.detect");

        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path rootPath = homeDir.resolve("library");
            Files.createDirectories(rootPath);
            Path note = rootPath.resolve("note.md");
            Files.writeString(note, "content");
            assumeTrue(NativeFileIdReaders.forCurrentPlatform().identityOf(note).isPresent(),
                    "native file identity isn't available on this machine");

            VfsRootId rootId = catalog.registerRoot("project-root", "Library", rootPath);
            catalog.scanRoot(rootId, ScanFilter.all());
            VfsFileId original = catalog.findFile(rootId, "note.md").orElseThrow().fileId();

            // Renamed outside the app.
            Files.move(note, rootPath.resolve("renamed.md"));
            VfsScanResult result = catalog.scanRoot(rootId, ScanFilter.all());

            assertTrue(catalog.findFile(rootId, "note.md").isEmpty(), "the old path should have no row");
            assertEquals(original, catalog.findFile(rootId, "renamed.md").orElseThrow().fileId(),
                    "a rename must keep the file_id, or metadata keyed on it is orphaned");

            // Reported as missing plus added with the same file_id, so listeners refresh both folders.
            assertEquals(List.of(original), result.missing().stream().map(VfsManagedFile::fileId).toList());
            assertEquals(List.of(original), result.added().stream().map(VfsManagedFile::fileId).toList());
            assertEquals("note.md", result.missing().get(0).relativePath());
            assertEquals("renamed.md", result.added().get(0).relativePath());
        } finally {
            Disposer.dispose(database);
        }
    }

    @Test
    void rescanRecognisesAFileMovedIntoASubdirectory() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.move.detect");

        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path rootPath = homeDir.resolve("library");
            Files.createDirectories(rootPath.resolve("docs"));
            Path note = rootPath.resolve("note.md");
            Files.writeString(note, "content");
            assumeTrue(NativeFileIdReaders.forCurrentPlatform().identityOf(note).isPresent(), "no native file id");

            VfsRootId rootId = catalog.registerRoot("project-root", "Library", rootPath);
            catalog.scanRoot(rootId, ScanFilter.all());
            VfsFileId original = catalog.findFile(rootId, "note.md").orElseThrow().fileId();

            Files.move(note, rootPath.resolve("docs").resolve("note.md"));
            catalog.scanRoot(rootId, ScanFilter.all());

            VfsManagedFile moved = catalog.findFile(rootId, "docs/note.md").orElseThrow();
            assertEquals(original, moved.fileId(), "a move into a subdirectory must be recognized too");
            // parent_path must change too, or the file isn't listed in its new directory.
            assertEquals(List.of("docs/note.md"),
                    catalog.listChildren(rootId, "docs").stream()
                            .map(VfsManagedFile::relativePath).toList());
        } finally {
            Disposer.dispose(database);
        }
    }

    @Test
    void ambiguousIdentitiesAreNotMatched() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.ambiguous");

        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path rootPath = homeDir.resolve("library");
            Files.createDirectories(rootPath);
            Path a = rootPath.resolve("a.txt");
            Files.writeString(a, "shared");
            Path b = rootPath.resolve("b.txt");
            try {
                // A hard link gives two paths the same native identity: one identity, several paths.
                Files.createLink(b, a);
            } catch (UnsupportedOperationException | IOException e) {
                assumeTrue(false, "hard links aren't supported on this machine");
            }
            assumeTrue(NativeFileIdReaders.forCurrentPlatform().identityOf(a)
                    .equals(NativeFileIdReaders.forCurrentPlatform().identityOf(b)),
                    "the hard link didn't share an identity, so there is no ambiguity to test");

            VfsRootId rootId = catalog.registerRoot("project-root", "Library", rootPath);
            catalog.scanRoot(rootId, ScanFilter.all());
            VfsFileId idA = catalog.findFile(rootId, "a.txt").orElseThrow().fileId();
            VfsFileId idB = catalog.findFile(rootId, "b.txt").orElseThrow().fileId();

            // Two paths vanish and two appear, all with one identity, so there's no telling which is which.
            Files.move(a, rootPath.resolve("x.txt"));
            Files.move(b, rootPath.resolve("y.txt"));
            catalog.scanRoot(rootId, ScanFilter.all());

            // MISSING rather than a guess: a wrong guess would silently attach metadata to another file.
            assertEquals(VfsFileStatus.MISSING, catalog.findFile(idA).orElseThrow().status());
            assertEquals(VfsFileStatus.MISSING, catalog.findFile(idB).orElseThrow().status());
            assertNotEquals(idA, catalog.findFile(rootId, "x.txt").orElseThrow().fileId());
            assertNotEquals(idB, catalog.findFile(rootId, "y.txt").orElseThrow().fileId());
        } finally {
            Disposer.dispose(database);
        }
    }

    /**
     * A move across directories reconciled the watcher's way, <b>non-recursively, one directory per pass</b>: the
     * disappearance and the appearance land in different refreshes, each seeing half of the move.
     */
    @Test
    void recognisesACrossDirectoryMoveSeenThroughPerDirectoryRefreshes() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.move.perdir");

        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path rootPath = homeDir.resolve("library");
            Files.createDirectories(rootPath.resolve("a"));
            Files.createDirectories(rootPath.resolve("b"));
            Path note = rootPath.resolve("a").resolve("note.md");
            Files.writeString(note, "content");
            assumeTrue(NativeFileIdReaders.forCurrentPlatform().identityOf(note).isPresent(), "no native file id");

            VfsRootId rootId = catalog.registerRoot("project-root", "Library", rootPath);
            catalog.scanRoot(rootId, ScanFilter.all());
            VfsFileId original = catalog.findFile(rootId, "a/note.md").orElseThrow().fileId();

            Files.move(note, rootPath.resolve("b").resolve("note.md"));
            // As the watcher does: one non-recursive pass for the source and one for the destination.
            catalog.refresh(rootId, "a", false, ScanFilter.all());
            catalog.refresh(rootId, "b", false, ScanFilter.all());

            assertEquals(original, catalog.findFile(rootId, "b/note.md").orElseThrow().fileId(),
                    "a move across directories must keep its file_id through per-directory refreshes");
        } finally {
            Disposer.dispose(database);
        }
    }

    /** A directory moved across directories keeps its file_id, and so do its files, whichever side refreshes first. */
    @Test
    void aDirectoryMovedAcrossDirectoriesKeepsItsIdentityInEitherRefreshOrder() throws Exception {
        verifyDirectoryMove(true);
        verifyDirectoryMove(false);
    }

    private void verifyDirectoryMove(boolean sourceFirst) throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.dirmove." + sourceFirst);
        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path root = homeDir.resolve("dirmove-" + sourceFirst);
            Path note = root.resolve("a").resolve("sub").resolve("x.md");
            Files.createDirectories(note.getParent());
            Files.createDirectories(root.resolve("b"));
            Files.writeString(note, "content");
            assumeTrue(NativeFileIdReaders.forCurrentPlatform().identityOf(note).isPresent(), "no native file id");
            VfsRootId rootId = catalog.registerRoot("project-root", "Library", root);
            catalog.scanRoot(rootId, ScanFilter.all());
            VfsFileId sub = catalog.findFile(rootId, "a/sub").orElseThrow().fileId();
            VfsFileId file = catalog.findFile(rootId, "a/sub/x.md").orElseThrow().fileId();

            Files.move(root.resolve("a").resolve("sub"), root.resolve("b").resolve("sub"));
            // As the watcher does: each affected directory non-recursively, in the order of an unordered set.
            List<String> order = sourceFirst ? List.of("a", "b", "b/sub") : List.of("b", "b/sub", "a");
            String which = sourceFirst ? "source first" : "destination first";
            catalog.refresh(rootId, order.get(0), false, ScanFilter.all());
            assertEquals(VfsFileStatus.MISSING, catalog.findFile(file).orElseThrow().status(),
                    which + ": after the first pass the file at the old path must not stay PRESENT");
            for (String dir : order.subList(1, order.size())) {
                catalog.refresh(rootId, dir, false, ScanFilter.all());
            }

            assertEquals(sub, catalog.findFile(rootId, "b/sub").orElseThrow().fileId(), which + ": directory keeps file_id");
            assertEquals(file, catalog.findFile(rootId, "b/sub/x.md").orElseThrow().fileId(), which + ": file keeps file_id");
            assertTrue(catalog.findFile(rootId, "a/sub").isEmpty(), which + ": no orphan row at the old path");
        } finally {
            Disposer.dispose(database);
        }
    }

    /** A directory renamed in place is matched by relocateMatched, and its children aren't reported missing. */
    @Test
    void renamingADirectoryInPlaceDoesNotReportItsChildrenMissing() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.dirrename.inplace");
        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path root = homeDir.resolve("dirrename");
            Path note = root.resolve("docs").resolve("old").resolve("x.md");
            Files.createDirectories(note.getParent());
            Files.writeString(note, "content");
            assumeTrue(NativeFileIdReaders.forCurrentPlatform().identityOf(note).isPresent(), "no native file id");
            VfsRootId rootId = catalog.registerRoot("project-root", "Library", root);
            catalog.scanRoot(rootId, ScanFilter.all());
            VfsFileId dir = catalog.findFile(rootId, "docs/old").orElseThrow().fileId();

            Files.move(root.resolve("docs").resolve("old"), root.resolve("docs").resolve("new"));
            VfsScanResult result = catalog.refresh(rootId, "docs", false, ScanFilter.all());

            assertEquals(dir, catalog.findFile(rootId, "docs/new").orElseThrow().fileId());
            assertTrue(result.missing().stream().noneMatch(f -> f.relativePath().startsWith("docs/old/")),
                    "an in-place rename must not report the children missing");
        } finally {
            Disposer.dispose(database);
        }
    }

    @Test
    void renameWithReplacementAtOldPathKeepsOriginalIdentity() throws Exception {
        verifyMoveWithReplacement("", "", false);
    }

    @Test
    void sourceFirstWatcherMoveWithReplacementKeepsOriginalIdentity() throws Exception {
        verifyMoveWithReplacement("a", "b", true);
    }

    @Test
    void destinationFirstWatcherMoveWithReplacementKeepsOriginalIdentity() throws Exception {
        verifyMoveWithReplacement("a", "b", false);
    }

    private void verifyMoveWithReplacement(String sourceDir, String destinationDir, boolean sourceFirst) throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.move.replaced." + sourceDir + sourceFirst);
        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path root = homeDir.resolve("replacement-library");
            Files.createDirectories(root.resolve(sourceDir));
            Files.createDirectories(root.resolve(destinationDir));
            Path oldPath = root.resolve(sourceDir).resolve("old.md");
            Path movedPath = root.resolve(destinationDir).resolve("moved.md");
            Files.writeString(oldPath, "original");
            assumeTrue(NativeFileIdReaders.forCurrentPlatform().identityOf(oldPath).isPresent());
            VfsRootId rootId = catalog.registerRoot("project-root", "Library", root);
            catalog.scanRoot(rootId, ScanFilter.all());
            String oldRel = root.relativize(oldPath).toString().replace('\\', '/');
            String movedRel = root.relativize(movedPath).toString().replace('\\', '/');
            VfsFileId original = catalog.findFile(rootId, oldRel).orElseThrow().fileId();
            String physicalOriginal = storedNativeId(database, oldRel);
            Files.move(oldPath, movedPath);
            Files.writeString(oldPath, "replacement");
            if (sourceDir.isEmpty()) {
                catalog.scanRoot(rootId, ScanFilter.all());
            } else if (sourceFirst) {
                catalog.refresh(rootId, sourceDir, false, ScanFilter.all());
                assertEquals(physicalOriginal, storedNativeId(database, oldRel),
                        "the original is still under the root, so the replacement must not take its identity");
                catalog.refresh(rootId, destinationDir, false, ScanFilter.all());
            } else {
                catalog.refresh(rootId, destinationDir, false, ScanFilter.all());
                catalog.refresh(rootId, sourceDir, false, ScanFilter.all());
            }
            assertEquals(original, catalog.findFile(rootId, movedRel).orElseThrow().fileId());
            assertNotEquals(original, catalog.findFile(rootId, oldRel).orElseThrow().fileId());
            assertEquals(physicalOriginal, storedNativeId(database, movedRel));
            assertEquals("original", Files.readString(catalog.findFile(original).orElseThrow().absolutePath()));
        } finally {
            Disposer.dispose(database);
        }
    }

    /** A watcher-style single-directory refresh must also update the identity of a file replaced in place. */
    @Test
    void perDirectoryRefreshRefreshesIdentityAfterInPlaceReplacement() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.nativeid.replaced.perdir");
        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path root = homeDir.resolve("library");
            Files.createDirectories(root.resolve("docs"));
            Path note = root.resolve("docs").resolve("note.md");
            Files.writeString(note, "v1");
            assumeTrue(NativeFileIdReaders.forCurrentPlatform().identityOf(note).isPresent(), "no native file id");
            VfsRootId rootId = catalog.registerRoot("project-root", "Library", root);
            catalog.scanRoot(rootId, ScanFilter.all());
            String first = storedNativeId(database, "docs/note.md");

            Path tmp = root.resolve("docs").resolve("note.md.tmp");
            Files.writeString(tmp, "v2");
            Files.move(tmp, note, StandardCopyOption.REPLACE_EXISTING);
            catalog.refresh(rootId, "docs", false, ScanFilter.all());

            String second = storedNativeId(database, "docs/note.md");
            assertNotEquals(first, second, "the directory refresh must update the identity itself");
            assertEquals(NativeFileIdReaders.forCurrentPlatform().identityOf(note).orElseThrow(), second);
        } finally {
            Disposer.dispose(database);
        }
    }

    /** A file replaced in place and later moved keeps its file_id through single-directory refreshes. */
    @Test
    void renameAfterInPlaceReplacementSeenThroughPerDirectoryRefreshesKeepsFileId() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.nativeid.replaced.then.moved");
        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path root = homeDir.resolve("library");
            Files.createDirectories(root.resolve("a"));
            Files.createDirectories(root.resolve("b"));
            Path note = root.resolve("a").resolve("note.md");
            Files.writeString(note, "v1");
            assumeTrue(NativeFileIdReaders.forCurrentPlatform().identityOf(note).isPresent(), "no native file id");
            VfsRootId rootId = catalog.registerRoot("project-root", "Library", root);
            catalog.scanRoot(rootId, ScanFilter.all());
            VfsFileId original = catalog.findFile(rootId, "a/note.md").orElseThrow().fileId();

            Path tmp = root.resolve("a").resolve("note.md.tmp");
            Files.writeString(tmp, "v2");
            Files.move(tmp, note, StandardCopyOption.REPLACE_EXISTING);
            catalog.refresh(rootId, "a", false, ScanFilter.all());

            Files.move(note, root.resolve("b").resolve("note.md"));
            catalog.refresh(rootId, "a", false, ScanFilter.all());
            catalog.refresh(rootId, "b", false, ScanFilter.all());

            assertEquals(original, catalog.findFile(rootId, "b/note.md").orElseThrow().fileId(),
                    "a replaced file must be recognized after a move, or its metadata is orphaned");
            assertTrue(catalog.findFile(rootId, "a/note.md").isEmpty(), "no row should be left at the old path");
        } finally {
            Disposer.dispose(database);
        }
    }

    /** An original moved out of the root (recycle bin, another folder) counts as gone; the identity is refreshed. */
    @Test
    void perDirectoryRefreshRefreshesIdentityWhenTheOriginalWasMovedOutOfTheRoot() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.nativeid.moved.outside");
        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path root = homeDir.resolve("library");
            Files.createDirectories(root.resolve("a"));
            Path outside = Files.createDirectories(homeDir.resolve("outside"));
            Path note = root.resolve("a").resolve("note.md");
            Files.writeString(note, "original");
            assumeTrue(NativeFileIdReaders.forCurrentPlatform().identityOf(note).isPresent(), "no native file id");
            VfsRootId rootId = catalog.registerRoot("project-root", "Library", root);
            catalog.scanRoot(rootId, ScanFilter.all());
            String physicalOriginal = storedNativeId(database, "a/note.md");

            Files.move(note, outside.resolve("note.md"));
            Files.writeString(note, "replacement");
            catalog.refresh(rootId, "a", false, ScanFilter.all());

            String stored = storedNativeId(database, "a/note.md");
            assertNotEquals(physicalOriginal, stored, "the original left the root, so the path gets a new identity");
            assertEquals(NativeFileIdReaders.forCurrentPlatform().identityOf(note).orElseThrow(), stored);
        } finally {
            Disposer.dispose(database);
        }
    }

    /** Reads the snapshot column directly, since VfsManagedFile doesn't expose the native id. */
    private static String storedNativeId(DatabaseHandle database, String relativePath) {
        return database.read(session -> session.queryOne(
                "select native_file_id from jdesk_vfs_files where relative_path = ?",
                row -> row.getString("native_file_id"), relativePath)).orElseThrow();
    }

    private DatabaseHandle openDatabase(String id) {
        DatabaseService service = new SqliteDatabaseService();
        return service.open(DatabaseDescriptor.builder(id)
                .owner(DatabaseOwner.application("tests"))
                .name(id)
                .build());
    }

    @Test
    void removeFileDropsTheRowSoThePathCanBeRegisteredAfresh() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.remove");

        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-remove", database);
            Path rootPath = Files.createDirectories(homeDir.resolve("library"));
            VfsRootId rootId = catalog.registerRoot("Library", "Library", rootPath);
            Files.createDirectories(rootPath.resolve("旧夹"));
            VfsFileId first = catalog.registerFile(rootId, "旧夹", true).fileId();

            catalog.removeFile(first);

            assertTrue(catalog.findFile(first).isEmpty(), "the row should be deleted, not marked");
            assertTrue(catalog.listChildren(rootId, "").isEmpty(), "a listing should no longer show it");

            VfsFileId second = catalog.registerFile(rootId, "旧夹", true).fileId();
            assertNotEquals(first, second, "registering the path again after removal should give a new file_id");

            catalog.removeFile(VfsFileId.of("不存在的-id"));
        } finally {
            Disposer.dispose(database);
        }
    }

    /**
     * Removing a subtree passes the directory and its contents at once, and <b>only</b> those rows go. Must not widen
     * the scope, for example by deleting by prefix: other rows under the root must survive.
     */
    @Test
    void removeFilesDropsEveryGivenRowAndLeavesTheRestAlone() {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.remove.many");

        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-remove-many", database);
            VfsRootId rootId = catalog.registerRoot("Library", "Library", homeDir.resolve("library"));
            VfsFileId dir = catalog.registerFile(rootId, "旧夹", true).fileId();
            VfsFileId inner = catalog.registerFile(rootId, "旧夹/a.md", false).fileId();
            VfsFileId deep = catalog.registerFile(rootId, "旧夹/子/b.md", false).fileId();
            VfsFileId sibling = catalog.registerFile(rootId, "留着.md", false).fileId();

            catalog.removeFiles(List.of(dir, inner, deep));

            assertTrue(catalog.findFile(dir).isEmpty());
            assertTrue(catalog.findFile(inner).isEmpty());
            assertTrue(catalog.findFile(deep).isEmpty());
            assertTrue(catalog.findFile(sibling).isPresent(), "rows not passed in must stay as they were");
            assertEquals(List.of("留着.md"),
                    catalog.listChildren(rootId, "").stream().map(VfsManagedFile::relativePath).toList());
        } finally {
            Disposer.dispose(database);
        }
    }

    /** An empty collection does nothing; running {@code in ()} would be a syntax error. */
    @Test
    void removeFilesOnAnEmptyCollectionChangesNothing() {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.remove.empty");

        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-remove-empty", database);
            VfsRootId rootId = catalog.registerRoot("Library", "Library", homeDir.resolve("library"));
            VfsFileId kept = catalog.registerFile(rootId, "留着.md", false).fileId();

            catalog.removeFiles(List.of());

            assertTrue(catalog.findFile(kept).isPresent());
        } finally {
            Disposer.dispose(database);
        }
    }

    /** Unknown ids don't affect the rest: as with {@code removeFile}, removing what's already gone isn't an error. */
    @Test
    void removeFilesIgnoresIdsThatAreNotInTheCatalog() {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.remove.unknown");

        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-remove-unknown", database);
            VfsRootId rootId = catalog.registerRoot("Library", "Library", homeDir.resolve("library"));
            VfsFileId real = catalog.registerFile(rootId, "旧夹/a.md", false).fileId();
            VfsFileId kept = catalog.registerFile(rootId, "留着.md", false).fileId();

            catalog.removeFiles(List.of(real, VfsFileId.of("不存在的-id")));

            assertTrue(catalog.findFile(real).isEmpty(), "the known ids are removed as usual");
            assertTrue(catalog.findFile(kept).isPresent());
        } finally {
            Disposer.dispose(database);
        }
    }

    /**
     * The batch loop covers <b>every batch</b>, including a partial last one. A package-private overload shrinks the
     * batch size from {@link SqliteVfsCatalog#DELETE_BATCH_SIZE} to 2 to run the same loop.
     */
    @Test
    void removeFilesDeletesEveryBatchIncludingTheLastPartialOne() {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.remove.batches");

        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-remove-batches", database);
            VfsRootId rootId = catalog.registerRoot("Library", "Library", homeDir.resolve("library"));
            VfsFileId f1 = catalog.registerFile(rootId, "f1.md", false).fileId();
            VfsFileId f2 = catalog.registerFile(rootId, "f2.md", false).fileId();
            VfsFileId f3 = catalog.registerFile(rootId, "f3.md", false).fileId();
            VfsFileId f4 = catalog.registerFile(rootId, "f4.md", false).fileId();
            VfsFileId f5 = catalog.registerFile(rootId, "f5.md", false).fileId();
            catalog.registerFile(rootId, "留着.md", false);

            // Five ids split as 2+2+1: the partial last batch is where index arithmetic tends to go wrong.
            catalog.removeFiles(List.of(f1, f2, f3, f4, f5), 2);

            assertEquals(List.of("留着.md"),
                    catalog.listChildren(rootId, "").stream().map(VfsManagedFile::relativePath).toList());
            assertTrue(SqliteVfsCatalog.DELETE_BATCH_SIZE <= 999,
                    "parameters per batch must stay under SQLite's SQLITE_MAX_VARIABLE_NUMBER (999 in older versions)");
        } finally {
            Disposer.dispose(database);
        }
    }

    @Test
    void registerFileRevivesAStaleRowWhenThePathIsBackOnDisk() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.register.revive");

        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-revive", database);
            Path rootPath = Files.createDirectories(homeDir.resolve("library"));
            VfsRootId rootId = catalog.registerRoot("Library", "Library", rootPath);
            Path dir = Files.createDirectories(rootPath.resolve("新建文件夹 7"));
            VfsFileId before = catalog.registerFile(rootId, "新建文件夹 7", true).fileId();

            // Deleted outside the app, so reconcile marks the row MISSING.
            Files.delete(dir);
            catalog.updateStatus(before, VfsFileStatus.MISSING);
            // Then a folder with the same name is created from the app.
            Files.createDirectories(dir);

            VfsManagedFile again = catalog.registerFile(rootId, "新建文件夹 7", true);

            assertEquals(before, again.fileId(),
                    "recreating the same path keeps the file_id, as a scan's reconcile does");
            assertEquals(VfsFileStatus.PRESENT, again.status(),
                    "registering means the caller knows it is on disk, so a MISSING row must not come back");
        } finally {
            Disposer.dispose(database);
        }
    }

    @Test
    void registerFileReportsMissingWhenThePathIsNotOnDiskYet() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.register.absent");

        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-absent", database);
            Path rootPath = Files.createDirectories(homeDir.resolve("library"));
            VfsRootId rootId = catalog.registerRoot("Library", "Library", rootPath);

            // A stable id for a path not on disk yet: both calls get the same id, and the status must be MISSING.
            VfsManagedFile first = catalog.registerFile(rootId, "inbox", true);
            VfsManagedFile second = catalog.registerFile(rootId, "inbox", true);

            assertEquals(first.fileId(), second.fileId(), "the id is stable");
            assertEquals(VfsFileStatus.MISSING, second.status(), "a path not on disk must be MISSING");
        } finally {
            Disposer.dispose(database);
        }
    }

    /**
     * Registering an unchanged path again <b>writes nothing</b>, on disk ({@code PRESENT}) or not yet
     * ({@code MISSING}); guards the early returns in {@code reconcileRegistered}. {@code updated_at} is stamped with a
     * sentinel that any update would replace with {@code now()}.
     */
    @Test
    void registerFileDoesNotWriteWhenNothingChanged() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.register.nowrite");

        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-nowrite", database);
            Path rootPath = Files.createDirectories(homeDir.resolve("library"));
            VfsRootId rootId = catalog.registerRoot("Library", "Library", rootPath);
            Files.createDirectories(rootPath.resolve("inbox"));
            VfsFileId onDisk = catalog.registerFile(rootId, "inbox", true).fileId();
            VfsFileId notOnDisk = catalog.registerFile(rootId, "未落盘", true).fileId();

            stampUpdatedAt(database, onDisk);
            stampUpdatedAt(database, notOnDisk);

            catalog.registerFile(rootId, "inbox", true);
            catalog.registerFile(rootId, "未落盘", true);

            assertEquals(SENTINEL_UPDATED_AT, readColumnLong(database, onDisk, "updated_at"),
                    "nothing changed on disk, so nothing may be written: an UPDATE per call would be silent "
                            + "write amplification for a frequent caller");
            assertEquals(SENTINEL_UPDATED_AT, readColumnLong(database, notOnDisk, "updated_at"),
                    "likewise for a row not on disk: already MISSING, it must not be marked again "
                            + "and have its content_version bumped, which would invalidate version-keyed caches");
        } finally {
            Disposer.dispose(database);
        }
    }

    /** A full reconcile neither follows a junction nor registers it; here it points to a tree outside the root. */
    @Test
    void fullReconcileNeitherFollowsNorListsAJunction() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.link.full");
        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path rootPath = homeDir.resolve("library");
            Files.createDirectories(rootPath.resolve("docs"));
            Files.writeString(rootPath.resolve("docs").resolve("a.md"), "a");
            Path outside = outsideTree();
            junction(rootPath.resolve("docs").resolve("shared"), outside);
            junction(rootPath.resolve("docs").resolve("sub"), outside.resolve("sub")); // Named like its target.
            VfsRootId rootId = catalog.registerRoot("library", "Library", rootPath);

            catalog.scanRoot(rootId, ScanFilter.all());

            assertEquals(VfsFileStatus.PRESENT, catalog.findFile(rootId, "docs/a.md").orElseThrow().status());
            for (String path : List.of("docs/shared", "docs/shared/c.md", "docs/shared/sub", "docs/shared/sub/b.md",
                    "docs/sub", "docs/sub/b.md")) {
                assertTrue(catalog.findFile(rootId, path).isEmpty(), path + " must not be registered");
            }
        } finally {
            Disposer.dispose(database);
        }
    }

    /** A non-recursive single-directory refresh, as the watcher runs, doesn't list a junction either. */
    @Test
    void nonRecursiveRefreshDoesNotListAJunction() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.link.flat");
        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path rootPath = homeDir.resolve("library");
            Files.createDirectories(rootPath);
            Files.writeString(rootPath.resolve("a.md"), "a");
            VfsRootId rootId = catalog.registerRoot("library", "Library", rootPath);
            catalog.scanRoot(rootId, ScanFilter.all());
            junction(rootPath.resolve("shared"), outsideTree());

            VfsScanResult result = catalog.refresh(rootId, "", false, ScanFilter.all());

            assertTrue(catalog.findFile(rootId, "shared").isEmpty(), "the junction itself must not be registered");
            assertTrue(result.added().isEmpty(), "nothing may be added: " + result.added());
        } finally {
            Disposer.dispose(database);
        }
    }

    /** A refresh starting at or below a junction, as the watcher may run for a new directory, registers nothing. */
    @Test
    void refreshStartingAtOrBelowAJunctionRegistersNothing() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.link.base");
        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path rootPath = homeDir.resolve("library");
            Files.createDirectories(rootPath);
            VfsRootId rootId = catalog.registerRoot("library", "Library", rootPath);
            catalog.scanRoot(rootId, ScanFilter.all());
            junction(rootPath.resolve("shared"), outsideTree());

            VfsScanResult atLink = catalog.refresh(rootId, "shared", false, ScanFilter.all());
            VfsScanResult belowLink = catalog.refresh(rootId, "shared/sub", false, ScanFilter.all());

            assertTrue(atLink.added().isEmpty(), "starting at the junction: " + atLink.added());
            assertTrue(belowLink.added().isEmpty(), "starting below the junction: " + belowLink.added());
            for (String path : List.of("shared/c.md", "shared/sub", "shared/sub/b.md")) {
                assertTrue(catalog.findFile(rootId, path).isEmpty(), path + " must not be registered");
            }
        } finally {
            Disposer.dispose(database);
        }
    }

    /** A directory replaced by a junction goes MISSING, even if the target holds files with the same names. */
    @Test
    void directoryReplacedByAJunctionGoesMissing() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.link.replaced");
        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path rootPath = homeDir.resolve("library");
            Path lib = rootPath.resolve("lib");
            Files.createDirectories(lib);
            Files.writeString(lib.resolve("c.md"), "c");
            VfsRootId rootId = catalog.registerRoot("library", "Library", rootPath);
            catalog.scanRoot(rootId, ScanFilter.all());
            Files.delete(lib.resolve("c.md"));
            Files.delete(lib);
            junction(lib, outsideTree());

            catalog.scanRoot(rootId, ScanFilter.all());

            assertEquals(VfsFileStatus.MISSING, catalog.findFile(rootId, "lib").orElseThrow().status());
            assertEquals(VfsFileStatus.MISSING, catalog.findFile(rootId, "lib/c.md").orElseThrow().status());
        } finally {
            Disposer.dispose(database);
        }
    }

    /**
     * A junction that denies listing, like {@code My Music} in the Documents folder, is a link and so counts as
     * absent, not unreadable; its old rows must not linger.
     */
    @Test
    void unlistableJunctionIsTreatedAsAbsentNotUnreadable() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.link.unlistable");
        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path rootPath = homeDir.resolve("library");
            Path music = rootPath.resolve("music");
            Files.createDirectories(music);
            Files.writeString(music.resolve("c.md"), "c");
            VfsRootId rootId = catalog.registerRoot("library", "Library", rootPath);
            catalog.scanRoot(rootId, ScanFilter.all());
            Files.delete(music.resolve("c.md"));
            Files.delete(music);
            Path link = junction(music, outsideTree());

            AclFileAttributeView acl = Files.getFileAttributeView(link, AclFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            List<AclEntry> original = acl.getAcl();
            UserPrincipal me = link.getFileSystem().getUserPrincipalLookupService()
                    .lookupPrincipalByName(System.getProperty("user.name"));
            List<AclEntry> denied = new ArrayList<>();
            denied.add(AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(me)
                    .setPermissions(AclEntryPermission.LIST_DIRECTORY).build());
            denied.addAll(original);
            acl.setAcl(denied);
            try {
                assumeTrue(cannotList(link), "the DENY didn't take effect on this machine");
                catalog.scanRoot(rootId, ScanFilter.all());
            } finally {
                acl.setAcl(original);
            }

            assertEquals(VfsFileStatus.MISSING, catalog.findFile(rootId, "music").orElseThrow().status());
            assertEquals(VfsFileStatus.MISSING, catalog.findFile(rootId, "music/c.md").orElseThrow().status());
        } finally {
            Disposer.dispose(database);
        }
    }

    /** Symbolic links to files and directories aren't listed either; skipped where symlinks can't be created. */
    @Test
    void symbolicLinksAreNotListed() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.link.symlink");
        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path rootPath = homeDir.resolve("library");
            Files.createDirectories(rootPath);
            Files.writeString(rootPath.resolve("a.md"), "a");
            Path outside = outsideTree();
            try {
                Files.createSymbolicLink(rootPath.resolve("dirlink"), outside);
                Files.createSymbolicLink(rootPath.resolve("filelink.md"), outside.resolve("c.md"));
            } catch (FileSystemException | UnsupportedOperationException unsupported) {
                assumeTrue(false, "can't create symbolic links on this machine: " + unsupported);
            }
            VfsRootId rootId = catalog.registerRoot("library", "Library", rootPath);

            catalog.scanRoot(rootId, ScanFilter.all());
            catalog.refresh(rootId, "", false, ScanFilter.all());

            assertEquals(VfsFileStatus.PRESENT, catalog.findFile(rootId, "a.md").orElseThrow().status());
            for (String path : List.of("dirlink", "dirlink/c.md", "filelink.md")) {
                assertTrue(catalog.findFile(rootId, path).isEmpty(), path + " must not be registered");
            }
        } finally {
            Disposer.dispose(database);
        }
    }

    /** A root reached through a junction is scanned normally, and so are its subdirectory refreshes. */
    @Test
    void rootReachedThroughAJunctionIsScannedNormally() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.link.root");
        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path realRoot = homeDir.resolve("real-library");
            Files.createDirectories(realRoot.resolve("sub"));
            Files.writeString(realRoot.resolve("a.md"), "a");
            Files.writeString(realRoot.resolve("sub").resolve("x.md"), "x");
            Path rootPath = junction(homeDir.resolve("library"), realRoot);
            VfsRootId rootId = catalog.registerRoot("library", "Library", rootPath);

            catalog.scanRoot(rootId, ScanFilter.all());
            Files.writeString(realRoot.resolve("sub").resolve("y.md"), "y");
            catalog.refresh(rootId, "sub", false, ScanFilter.all());

            for (String path : List.of("a.md", "sub", "sub/x.md", "sub/y.md")) {
                assertEquals(VfsFileStatus.PRESENT, catalog.findFile(rootId, path).orElseThrow().status(), path);
            }
        } finally {
            Disposer.dispose(database);
        }
    }

    /**
     * Registering a single file judges links as a walk does: a path that is or lies under a link is missing.
     * Otherwise a frequently registered directory replaced by a junction would flip between PRESENT and MISSING.
     */
    @Test
    void registerFileTreatsPathsThroughALinkAsMissing() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.link.register");
        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path rootPath = homeDir.resolve("library");
            Path inbox = rootPath.resolve("inbox");
            Files.createDirectories(inbox);
            VfsRootId rootId = catalog.registerRoot("library", "Library", rootPath);
            assertEquals(VfsFileStatus.PRESENT, catalog.registerFile(rootId, "inbox", true).status());
            Files.delete(inbox);
            junction(inbox, outsideTree());

            VfsManagedFile linked = catalog.registerFile(rootId, "inbox", true);
            VfsManagedFile throughLink = catalog.registerFile(rootId, "inbox/c.md", false);

            assertEquals(VfsFileStatus.MISSING, linked.status(), "a registered row whose path became a link");
            assertEquals(VfsFileStatus.MISSING, throughLink.status(), "a file newly registered through a link");
        } finally {
            Disposer.dispose(database);
        }
    }

    /** {@code refreshFile} doesn't follow links either, even if the target has a file of that name. */
    @Test
    void refreshFileTreatsPathsThroughALinkAsMissing() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.link.refreshfile");
        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path rootPath = homeDir.resolve("library");
            Path lib = rootPath.resolve("lib");
            Files.createDirectories(lib);
            Files.writeString(lib.resolve("c.md"), "c");
            VfsRootId rootId = catalog.registerRoot("library", "Library", rootPath);
            catalog.scanRoot(rootId, ScanFilter.all());
            VfsFileId fileId = catalog.findFile(rootId, "lib/c.md").orElseThrow().fileId();
            Files.delete(lib.resolve("c.md"));
            Files.delete(lib);
            junction(lib, outsideTree());

            assertEquals(VfsFileStatus.MISSING, catalog.refreshFile(fileId).status());
        } finally {
            Disposer.dispose(database);
        }
    }

    /** A junction root that can't be listed this pass must not be skipped as a link, or the whole tree goes missing. */
    @Test
    void unlistableJunctionRootKeepsItsRows() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.link.root.unlistable");
        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path realRoot = homeDir.resolve("real-library");
            Files.createDirectories(realRoot);
            Files.writeString(realRoot.resolve("a.md"), "a");
            Path rootPath = junction(homeDir.resolve("library"), realRoot);
            VfsRootId rootId = catalog.registerRoot("library", "Library", rootPath);
            catalog.scanRoot(rootId, ScanFilter.all());

            AclFileAttributeView acl = Files.getFileAttributeView(rootPath, AclFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            List<AclEntry> original = acl.getAcl();
            UserPrincipal me = rootPath.getFileSystem().getUserPrincipalLookupService()
                    .lookupPrincipalByName(System.getProperty("user.name"));
            List<AclEntry> denied = new ArrayList<>();
            denied.add(AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(me)
                    .setPermissions(AclEntryPermission.LIST_DIRECTORY).build());
            denied.addAll(original);
            acl.setAcl(denied);
            try {
                assumeTrue(cannotList(rootPath), "the DENY didn't take effect on this machine");
                catalog.scanRoot(rootId, ScanFilter.all());
            } finally {
                acl.setAcl(original);
            }

            assertEquals(VfsFileStatus.PRESENT, catalog.findFile(rootId, "a.md").orElseThrow().status(),
                    "the root just couldn't be read this pass");
        } finally {
            Disposer.dispose(database);
        }
    }

    /** New rows aren't read-only, whether added by {@code registerFile} or by a scan. */
    @Test
    void newlyRegisteredFilesAreNotReadOnly() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.readonly.default");
        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path rootPath = Files.createDirectories(homeDir.resolve("library"));
            Files.writeString(rootPath.resolve("registered.md"), "r");
            Files.writeString(rootPath.resolve("scanned.md"), "s");
            VfsRootId rootId = catalog.registerRoot("library", "Library", rootPath);

            VfsManagedFile registered = catalog.registerFile(rootId, "registered.md", false);
            catalog.scanRoot(rootId, ScanFilter.all());

            assertFalse(registered.readOnly());
            assertFalse(catalog.findFile(rootId, "scanned.md").orElseThrow().readOnly());
        } finally {
            Disposer.dispose(database);
        }
    }

    /** The read-only flag reads back and can be cleared, without bumping {@code content_version}. */
    @Test
    void setReadOnlyIsReadBackAndCanBeCleared() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.readonly.set");
        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path rootPath = Files.createDirectories(homeDir.resolve("library"));
            Files.writeString(rootPath.resolve("note.md"), "n");
            VfsRootId rootId = catalog.registerRoot("library", "Library", rootPath);
            VfsManagedFile before = catalog.registerFile(rootId, "note.md", false);

            assertTrue(catalog.setReadOnly(before.fileId(), true));
            VfsManagedFile locked = catalog.findFile(before.fileId()).orElseThrow();
            assertTrue(locked.readOnly());
            assertTrue(catalog.findFile(rootId, "note.md").orElseThrow().readOnly());
            assertEquals(before.contentVersion(), locked.contentVersion(), "the read-only flag isn't a content change");

            assertTrue(catalog.setReadOnly(before.fileId(), false));
            assertFalse(catalog.findFile(before.fileId()).orElseThrow().readOnly());
        } finally {
            Disposer.dispose(database);
        }
    }

    /** Renames and moves inside the app go through {@code moveFile} on the same row, so read-only follows. */
    @Test
    void readOnlyFollowsTheRowThroughMoveFile() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.readonly.move");
        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path rootPath = Files.createDirectories(homeDir.resolve("library"));
            Files.createDirectories(rootPath.resolve("docs"));
            Files.writeString(rootPath.resolve("note.md"), "n");
            VfsRootId rootId = catalog.registerRoot("library", "Library", rootPath);
            VfsFileId fileId = catalog.registerFile(rootId, "note.md", false).fileId();
            catalog.setReadOnly(fileId, true);

            Files.move(rootPath.resolve("note.md"), rootPath.resolve("renamed.md"));
            VfsManagedFile renamed = catalog.moveFile(fileId, rootId, "renamed.md");
            Files.move(rootPath.resolve("renamed.md"), rootPath.resolve("docs").resolve("renamed.md"));
            VfsManagedFile moved = catalog.moveFile(fileId, rootId, "docs/renamed.md");

            assertTrue(renamed.readOnly(), "rename");
            assertTrue(moved.readOnly(), "move");
            assertTrue(catalog.findFile(rootId, "docs/renamed.md").orElseThrow().readOnly());
        } finally {
            Disposer.dispose(database);
        }
    }

    /**
     * A full rescan, a directory refresh, {@code refreshFile} and {@code registerFile} rewrite the row of a changed
     * file, and the read-only flag must survive each.
     */
    @Test
    void readOnlySurvivesRescanAndReconcileOfAChangedFile() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.readonly.reconcile");
        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path rootPath = homeDir.resolve("library");
            Path note = Files.createDirectories(rootPath.resolve("docs")).resolve("note.md");
            Files.writeString(note, "v1");
            VfsRootId rootId = catalog.registerRoot("library", "Library", rootPath);
            catalog.scanRoot(rootId, ScanFilter.all());
            VfsFileId fileId = catalog.findFile(rootId, "docs/note.md").orElseThrow().fileId();
            catalog.setReadOnly(fileId, true);
            VfsManagedFile before = catalog.findFile(fileId).orElseThrow();
            long base = before.modifiedAt();

            rewrite(note, "v2-longer", base + 5_000);
            VfsScanResult fullScan = catalog.scanRoot(rootId, ScanFilter.all());
            rewrite(note, "v2-longer", base + 10_000); // Only the mtime changes.
            VfsScanResult dirRefresh = catalog.refresh(rootId, "docs", false, ScanFilter.all());
            rewrite(note, "v3-even-longer", base + 15_000);
            VfsManagedFile refreshed = catalog.refreshFile(fileId);
            rewrite(note, "v4", base + 20_000);
            VfsManagedFile registered = catalog.registerFile(rootId, "docs/note.md", false);

            assertEquals(List.of(true), fullScan.updated().stream().map(VfsManagedFile::readOnly).toList(), "rescan");
            assertEquals(List.of(true), dirRefresh.updated().stream().map(VfsManagedFile::readOnly).toList(), "dir refresh");
            assertTrue(refreshed.readOnly(), "refreshFile");
            assertTrue(registered.readOnly(), "registerFile");
            assertEquals(before.contentVersion() + 4, registered.contentVersion(), "precondition: all four rewrote it");
        } finally {
            Disposer.dispose(database);
        }
    }

    /** Read-only stays on a MISSING row and after a revival at the same path; a MISSING row still counts as found. */
    @Test
    void readOnlySurvivesMissingAndRevivalAtTheSamePath() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.readonly.missing");
        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path rootPath = Files.createDirectories(homeDir.resolve("library"));
            Path note = rootPath.resolve("note.md");
            Files.writeString(note, "n");
            VfsRootId rootId = catalog.registerRoot("library", "Library", rootPath);
            catalog.scanRoot(rootId, ScanFilter.all());
            VfsFileId fileId = catalog.findFile(rootId, "note.md").orElseThrow().fileId();
            catalog.setReadOnly(fileId, true);

            Files.delete(note);
            VfsManagedFile missing = catalog.scanRoot(rootId, ScanFilter.all()).missing().get(0);
            boolean foundWhileMissing = catalog.setReadOnly(fileId, true);
            Files.writeString(note, "n");
            VfsManagedFile revived = catalog.scanRoot(rootId, ScanFilter.all()).added().get(0);

            assertEquals(VfsFileStatus.MISSING, missing.status());
            assertTrue(missing.readOnly(), "going MISSING keeps read-only");
            assertTrue(foundWhileMissing, "a MISSING row still counts as found");
            assertEquals(fileId, revived.fileId(), "precondition: the same row is revived at its path");
            assertTrue(revived.readOnly(), "read-only survives the revival");
        } finally {
            Disposer.dispose(database);
        }
    }

    /** Read-only follows a file renamed outside the app that reconcile matches by native identity. */
    @Test
    void readOnlyFollowsAFileRenamedOutsideTheApp() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.readonly.external.rename");
        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path rootPath = Files.createDirectories(homeDir.resolve("library"));
            Path note = rootPath.resolve("note.md");
            Files.writeString(note, "content");
            assumeTrue(NativeFileIdReaders.forCurrentPlatform().identityOf(note).isPresent(),
                    "native file identity isn't available on this machine");
            VfsRootId rootId = catalog.registerRoot("library", "Library", rootPath);
            catalog.scanRoot(rootId, ScanFilter.all());
            VfsFileId fileId = catalog.findFile(rootId, "note.md").orElseThrow().fileId();
            catalog.setReadOnly(fileId, true);

            Files.move(note, rootPath.resolve("renamed.md"));
            catalog.scanRoot(rootId, ScanFilter.all());

            VfsManagedFile renamed = catalog.findFile(rootId, "renamed.md").orElseThrow();
            assertEquals(fileId, renamed.fileId(), "precondition: reconcile matched the same row");
            assertTrue(renamed.readOnly());
        } finally {
            Disposer.dispose(database);
        }
    }

    /**
     * {@code setReadOnly} returns false for an id never registered or removed by {@code removeFile}; registering the
     * path again gives a new identity that isn't read-only.
     */
    @Test
    void setReadOnlyReturnsFalseWhenTheRowDoesNotExist() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = openDatabase("vfs.readonly.absent");
        try {
            SqliteVfsCatalog catalog = new SqliteVfsCatalog("library-a", database);
            Path rootPath = Files.createDirectories(homeDir.resolve("library"));
            Files.writeString(rootPath.resolve("note.md"), "n");
            VfsRootId rootId = catalog.registerRoot("library", "Library", rootPath);
            VfsFileId removed = catalog.registerFile(rootId, "note.md", false).fileId();
            catalog.setReadOnly(removed, true);
            catalog.removeFile(removed);

            assertFalse(catalog.setReadOnly(VfsFileId.of("no-such-file"), true), "never registered");
            assertFalse(catalog.setReadOnly(removed, true), "row removed");
            VfsManagedFile again = catalog.registerFile(rootId, "note.md", false);
            assertNotEquals(removed, again.fileId());
            assertFalse(again.readOnly(), "a new identity doesn't inherit the old row's read-only flag");
        } finally {
            Disposer.dispose(database);
        }
    }

    /** Rewrites the content and sets the mtime, since two writes in one millisecond may keep it. */
    private static void rewrite(Path file, String content, long modifiedAt) throws IOException {
        Files.writeString(file, content);
        Files.setLastModifiedTime(file, FileTime.fromMillis(modifiedAt));
    }

    /** Junctions made by a test; must go first, as {@code @TempDir} cleanup follows them into their targets. */
    private final List<Path> junctions = new ArrayList<>();

    @AfterEach
    void removeJunctions() throws IOException {
        for (Path link : junctions) {
            Files.deleteIfExists(link);
        }
    }

    /** Makes a junction with {@code mklink /J}, no admin rights needed; skips the test off Windows or on failure. */
    private Path junction(Path link, Path target) throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"), "junctions need NTFS");
        Process mklink = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start();
        String output = new String(mklink.getInputStream().readAllBytes(), Charset.defaultCharset());
        boolean created = mklink.waitFor() == 0 && Files.isDirectory(link);
        if (created) {
            junctions.add(link);
        }
        assumeTrue(created, "mklink /J failed: " + output);
        return link;
    }

    /** A small tree outside the root: {@code c.md} and {@code sub/b.md}. */
    private Path outsideTree() throws IOException {
        Path outside = homeDir.resolve("outside");
        Files.createDirectories(outside.resolve("sub"));
        Files.writeString(outside.resolve("c.md"), "c");
        Files.writeString(outside.resolve("sub").resolve("b.md"), "b");
        return outside;
    }

    /** A value a real {@code now()} never returns, so a surviving sentinel means the row was never written. */
    private static final long SENTINEL_UPDATED_AT = 7L;

    private static void stampUpdatedAt(DatabaseHandle database, VfsFileId fileId) {
        database.transaction(session -> session.update(
                "update jdesk_vfs_files set updated_at = ? where file_id = ?",
                SENTINEL_UPDATED_AT, fileId.value()));
    }

    private static long readColumnLong(DatabaseHandle database, VfsFileId fileId, String column) {
        return database.read(session -> session.queryOne(
                "select " + column + " as v from jdesk_vfs_files where file_id = ?",
                row -> row.getLong("v"), fileId.value())).orElseThrow();
    }
}
