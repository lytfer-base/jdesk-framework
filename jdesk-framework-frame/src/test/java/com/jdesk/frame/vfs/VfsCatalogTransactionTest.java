package com.jdesk.frame.vfs;

import com.jdesk.core.app.PathManager;
import com.jdesk.core.disposer.Disposer;
import com.jdesk.core.vfs.VfsCatalog;
import com.jdesk.core.vfs.VfsFileId;
import com.jdesk.core.vfs.VfsFileStatus;
import com.jdesk.core.vfs.VfsManagedFile;
import com.jdesk.core.vfs.VfsRootId;
import com.jdesk.frame.storage.DatabaseDescriptor;
import com.jdesk.frame.storage.DatabaseHandle;
import com.jdesk.frame.storage.DatabaseOwner;
import com.jdesk.frame.storage.DatabaseSession;
import com.jdesk.frame.storage.StorageException;
import com.jdesk.frame.storage.sqlite.SqliteDatabaseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class VfsCatalogTransactionTest {
    @TempDir
    Path home;
    final SqliteDatabaseService service = new SqliteDatabaseService();
    DatabaseHandle database;
    SqliteVfsCatalog catalog;
    VfsRootId root;
    final VfsFileId supplied = VfsFileId.of("12345678-1234-4123-8123-123456789abc");

    @BeforeEach
    void open() throws Exception {
        PathManager.setHomePathForTests(home);
        database = service.open(DatabaseDescriptor.builder("catalog.test")
                .owner(DatabaseOwner.application("tests")).name("catalog.test").build());
        catalog = new SqliteVfsCatalog("catalog.test", database, Locale.ENGLISH);
        root = catalog.registerRoot("files", "Files", Files.createDirectory(home.resolve("files")));
    }

    @AfterEach
    void close() {
        Disposer.clearAll();
        PathManager.resetForTests();
    }

    @Test
    void suppliedIdentityReservesMissingFileBeforeDiskWrite() {
        VfsManagedFile row = reserve(supplied, "alpha.txt");
        assertEquals(supplied, row.fileId());
        assertEquals(VfsFileStatus.MISSING, row.status());
        assertFalse(Files.exists(row.absolutePath()));
        assertEquals(row, catalog.findFile(supplied).orElseThrow());
    }

    @Test
    void repeatingReservationDoesNotReconcileNewDiskContent() throws Exception {
        VfsManagedFile before = reserve(supplied, "alpha.txt");
        Files.writeString(before.absolutePath(), "new content");
        assertEquals(before, reserve(supplied, "alpha.txt"));
        assertEquals(VfsFileStatus.PRESENT, catalog.registerFile(root, "alpha.txt", false).status());
    }

    @Test
    void transactionViewSharesUncommittedWritesAndCommitsAuxiliaryRow() {
        createAuxiliary();
        database.transaction(session -> {
            VfsCatalogTransaction view = catalog.inTransaction(session);
            VfsManagedFile row = view.registerFileWithId(root, "alpha.txt", false, supplied);
            session.update("insert into auxiliary values (?, ?)", supplied.value(), "kept");
            assertEquals(row, view.findFile(supplied).orElseThrow());
            assertEquals(row, view.findFile(root, "alpha.txt").orElseThrow());
            return null;
        });
        assertTrue(catalog.findFile(supplied).isPresent());
        assertEquals(1L, auxiliaryCount());
    }

    @Test
    void auxiliaryFailureRollsBackReservation() {
        createAuxiliary();
        assertThrows(StorageException.class, () -> database.transaction(session -> {
            catalog.inTransaction(session).registerFileWithId(root, "alpha.txt", false, supplied);
            session.update("insert into auxiliary values (?, ?)", supplied.value(), null);
            return null;
        }));
        assertTrue(catalog.findFile(supplied).isEmpty());
        assertEquals(0L, auxiliaryCount());
    }

    @Test
    void suppliedIdentityRejectsOtherPathRootAndKindWithoutChangingRows() {
        VfsManagedFile before = reserve(supplied, "alpha.txt");
        VfsRootId otherRoot = catalog.registerRoot("other", "Other", home.resolve("other"));
        assertThrows(StorageException.class, () -> reserve(supplied, "beta.txt"));
        assertThrows(StorageException.class,
                () -> catalog.registerFileWithId(otherRoot, "alpha.txt", false, supplied));
        assertThrows(StorageException.class,
                () -> catalog.registerFileWithId(root, "alpha.txt", true, supplied));
        assertEquals(List.of(before), catalog.listAll(root));
        assertTrue(catalog.listAll(otherRoot).isEmpty());
    }

    @Test
    void occupiedPathIsNeverReassignedEvenIfMissing() {
        VfsManagedFile before = catalog.registerFile(root, "alpha.txt", false);
        assertThrows(StorageException.class, () -> reserve(supplied, "alpha.txt"));
        assertEquals(before, catalog.findFile(root, "alpha.txt").orElseThrow());
        assertTrue(catalog.findFile(supplied).isEmpty());
    }

    @Test
    void invalidUuidIsRejectedWithoutChangingTheLegacyIdValueType() {
        for (String text : List.of("legacy-id", "1-1-1-1-1", "12345678-1234-4123-8123-123456789abz")) {
            VfsFileId id = VfsFileId.of(text);
            assertThrows(IllegalArgumentException.class, () -> reserve(id, "alpha.txt"));
        }
        assertTrue(catalog.listAll(root).isEmpty());
    }

    @Test
    void reservationRequiresAnExistingRoot() {
        assertThrows(StorageException.class, () -> catalog.registerFileWithId(
                VfsRootId.of("absent-root"), "alpha.txt", false, supplied));
        assertTrue(catalog.findFile(supplied).isEmpty());
    }

    @Test
    void rekeyPreservesContentFlagsAndStoredMetadata() throws Exception {
        Files.writeString(home.resolve("files/alpha.txt"), "kept");
        VfsManagedFile before = catalog.registerFile(root, "alpha.txt", false);
        catalog.setReadOnly(before.fileId(), true);
        List<String> metadata = metadata(before.fileId());
        VfsManagedFile changed = catalog.rekeyFile(before.fileId(), supplied);
        assertEquals(metadata, metadata(supplied));
        assertEquals(before.relativePath(), changed.relativePath());
        assertEquals(before.contentVersion(), changed.contentVersion());
        assertTrue(changed.readOnly());
        assertTrue(catalog.findFile(before.fileId()).isEmpty());
        assertEquals(changed, catalog.rekeyFile(supplied, supplied));
        assertEquals("kept", Files.readString(changed.absolutePath()));
    }

    @Test
    void rekeyCollisionPreservesBothRows() {
        VfsManagedFile first = catalog.registerFile(root, "alpha.txt", false);
        VfsManagedFile second = reserve(supplied, "beta.txt");
        assertThrows(StorageException.class, () -> catalog.rekeyFile(first.fileId(), supplied));
        assertEquals(first, catalog.findFile(first.fileId()).orElseThrow());
        assertEquals(second, catalog.findFile(supplied).orElseThrow());
    }

    @Test
    void rekeyRejectsMissingSourceAndInvalidTarget() {
        VfsManagedFile row = reserve(supplied, "alpha.txt");
        assertThrows(StorageException.class, () -> catalog.rekeyFile(VfsFileId.of("absent"), supplied));
        assertThrows(IllegalArgumentException.class, () -> catalog.rekeyFile(supplied, VfsFileId.of("legacy")));
        assertEquals(row, catalog.findFile(supplied).orElseThrow());
    }

    @Test
    void auxiliaryFailureRollsBackRekeyAndLocalMutations() throws Exception {
        createAuxiliary();
        Files.writeString(home.resolve("files/alpha.txt"), "kept");
        VfsManagedFile before = catalog.registerFile(root, "alpha.txt", false);
        assertThrows(StorageException.class, () -> database.transaction(session -> {
            VfsCatalogTransaction view = catalog.inTransaction(session);
            view.rekeyFile(before.fileId(), supplied);
            view.setReadOnly(supplied, true);
            view.updateStatus(supplied, VfsFileStatus.PRESENT);
            session.update("insert into auxiliary values (?, ?)", supplied.value(), null);
            return null;
        }));
        assertEquals(before, catalog.findFile(before.fileId()).orElseThrow());
        assertTrue(catalog.findFile(supplied).isEmpty());
        assertEquals(0L, auxiliaryCount());
    }

    @Test
    void localRemovalUsesTheSameTransaction() {
        database.transaction(session -> {
            VfsCatalogTransaction view = catalog.inTransaction(session);
            view.registerFileWithId(root, "alpha.txt", false, supplied);
            view.removeFile(supplied);
            assertTrue(view.findFile(supplied).isEmpty());
            view.registerFileWithId(root, "beta.txt", false, supplied);
            view.removeFiles(List.of(supplied));
            assertTrue(view.findFile(supplied).isEmpty());
            return null;
        });
        assertTrue(catalog.listAll(root).isEmpty());
    }

    @Test
    void removalRollsBackWithTheCallersFailure() {
        VfsManagedFile before = reserve(supplied, "alpha.txt");
        assertThrows(IllegalStateException.class, () -> database.transaction(session -> {
            catalog.inTransaction(session).removeFiles(List.of(supplied));
            throw new IllegalStateException("rollback");
        }));
        assertEquals(before, catalog.findFile(supplied).orElseThrow());
    }

    @Test
    void readAndNonTransactionalWriteSessionsAreRejected() {
        database.read(session -> {
            assertThrows(StorageException.class, () -> catalog.inTransaction(session));
            return null;
        });
        database.write(session -> {
            assertThrows(StorageException.class, () -> catalog.inTransaction(session));
            return null;
        });
    }

    @Test
    void anotherHandlesTransactionIsRejected() {
        DatabaseHandle other = new SqliteDatabaseService().open(DatabaseDescriptor.builder("other.test")
                .owner(DatabaseOwner.application("tests")).name("other.test").build());
        other.transaction(session -> {
            assertThrows(StorageException.class, () -> catalog.inTransaction(session));
            return null;
        });
        assertTrue(catalog.listAll(root).isEmpty());
    }

    @Test
    void anotherThreadCannotCreateOrUseTheView() {
        database.transaction(session -> {
            VfsCatalogTransaction view = catalog.inTransaction(session);
            FutureTask<Boolean> result = new FutureTask<>(() -> {
                assertThrows(StorageException.class, () -> catalog.inTransaction(session));
                assertThrows(StorageException.class,
                        () -> view.registerFileWithId(root, "alpha.txt", false, supplied));
                assertThrows(StorageException.class, () -> view.findFile(supplied));
                return true;
            });
            Thread thread = new Thread(result, "catalog-view-test");
            thread.setDaemon(true);
            thread.start();
            assertTrue(assertDoesNotThrow(() -> result.get(2, TimeUnit.SECONDS)));
            return null;
        });
        assertTrue(catalog.listAll(root).isEmpty());
    }

    @Test
    void completedViewIsRejectedEvenOnTheSameWriterInANewTransaction() {
        AtomicReference<DatabaseSession> oldSession = new AtomicReference<>();
        VfsCatalogTransaction old = database.transaction(session -> {
            oldSession.set(session);
            return catalog.inTransaction(session);
        });
        database.transaction(session -> {
            assertThrows(StorageException.class, () -> catalog.inTransaction(oldSession.get()));
            assertThrows(StorageException.class,
                    () -> old.registerFileWithId(root, "alpha.txt", false, supplied));
            assertThrows(StorageException.class, () -> old.findFile(supplied));
            assertThrows(StorageException.class, () -> old.removeFiles(List.of()));
            return null;
        });
    }

    @Test
    void rolledBackViewAndClosedCatalogCannotBeUsed() {
        AtomicReference<VfsCatalogTransaction> saved = new AtomicReference<>();
        assertThrows(IllegalStateException.class, () -> database.transaction(session -> {
            saved.set(catalog.inTransaction(session));
            throw new IllegalStateException("rollback");
        }));
        database.transaction(session -> {
            assertThrows(StorageException.class, () -> saved.get().findFile(supplied));
            VfsCatalogTransaction current = catalog.inTransaction(session);
            catalog.close();
            assertThrows(StorageException.class, () -> current.findFile(supplied));
            return null;
        });
    }

    @Test
    void nestedConvenienceWriteStillFailsFast() {
        database.transaction(session -> {
            assertThrows(StorageException.class, () -> catalog.registerFile(root, "alpha.txt", false));
            assertThrows(StorageException.class, () -> reserve(supplied, "alpha.txt"));
            return null;
        });
    }

    @Test
    void borrowedHandleAcceptsItsOwnersTransaction() {
        DatabaseHandle borrowed = service.borrow(DatabaseDescriptor.builder("catalog.test")
                .owner(DatabaseOwner.application("tests")).name("catalog.test").build());
        SqliteVfsCatalog second = new SqliteVfsCatalog("catalog.test", borrowed, Locale.ENGLISH);
        borrowed.transaction(session -> {
            second.inTransaction(session).registerFileWithId(root, "alpha.txt", false, supplied);
            return null;
        });
        assertTrue(catalog.findFile(supplied).isPresent());
    }

    private VfsManagedFile reserve(VfsFileId id, String path) {
        return ((VfsCatalog.IdentityEditor) catalog).registerFileWithId(root, path, false, id);
    }

    private void createAuxiliary() {
        database.transaction(session -> session.update("create table auxiliary (id text primary key, value text not null)"));
    }

    private long auxiliaryCount() {
        return database.read(session -> session.queryOne("select count(*) as n from auxiliary",
                row -> row.getLong("n")).orElseThrow());
    }

    private List<String> metadata(VfsFileId id) {
        return database.read(session -> session.queryOne("""
                select root_id, relative_path, parent_path, file_name, hex(file_name_sort) as sort_key,
                       status, directory, size, modified_at, file_created_at, content_version,
                       created_at, updated_at, native_file_id, read_only
                from jdesk_vfs_files where file_id = ?
                """, row -> List.of(row.getString("root_id"), row.getString("relative_path"),
                row.getString("parent_path"), row.getString("file_name"), row.getString("sort_key"),
                row.getString("status"), row.getString("directory"), row.getString("size"),
                row.getString("modified_at"), row.getString("file_created_at"), row.getString("content_version"),
                row.getString("created_at"), row.getString("updated_at"), row.getString("native_file_id"),
                row.getString("read_only")), id.value()).orElseThrow());
    }
}
