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

    @Test
    void measuredObservationCompletesReservationWithoutReadingDisk() {
        VfsManagedFile before = reserve(supplied, "absent.png");
        catalog.setReadOnly(supplied, true);
        VfsManagedFile after = observe(42, 200, 100, "native-1");
        assertFalse(Files.exists(after.absolutePath()));
        assertEquals(VfsFileStatus.PRESENT, after.status());
        assertEquals(42, after.size());
        assertEquals(200, after.modifiedAt());
        assertEquals(100, after.fileCreatedAt());
        assertEquals(before.contentVersion() + 1, after.contentVersion());
        assertTrue(after.readOnly());
        assertEquals("native-1", metadata(supplied).get(13));
    }

    @Test
    void relocationKeepsIdentityAndObservationWithoutReadingDisk() {
        VfsManagedFile before = reserve(supplied, "old.png");
        catalog.setReadOnly(supplied, true);
        VfsManagedFile after = database.transaction(session ->
                catalog.inTransaction(session).relocateFile(supplied, root, "nested/new.png"));
        assertFalse(Files.exists(after.absolutePath()));
        assertTrue(catalog.findFile(root, "old.png").isEmpty());
        assertEquals(supplied, catalog.findFile(root, "nested/new.png").orElseThrow().fileId());
        assertEquals(before.contentVersion() + 1, after.contentVersion());
        assertEquals(before.status(), after.status());
        assertEquals(before.size(), after.size());
        assertEquals(before.modifiedAt(), after.modifiedAt());
        assertTrue(after.readOnly());
        assertEquals("nested", metadata(supplied).get(2));
        assertEquals("new.png", metadata(supplied).get(3));
        assertEquals(java.util.HexFormat.of().withUpperCase().formatHex(
                VfsFileNameSort.key(VfsFileNameSort.collatorFor(Locale.ENGLISH), "new.png")),
                metadata(supplied).get(4));
    }

    @Test
    void repeatedObservationPreservesVersionAndUnavailableNativeIdentity() {
        reserve(supplied, "absent.png");
        VfsManagedFile first = observe(42, 200, 100, "native-1");
        VfsManagedFile repeated = observe(42, 200, 100, null);
        assertEquals(first, repeated);
        assertEquals("native-1", metadata(supplied).get(13));
        VfsManagedFile changedMetadata = observe(42, 200, 90, "");
        assertEquals(first.contentVersion(), changedMetadata.contentVersion());
        assertEquals(90, changedMetadata.fileCreatedAt());
        assertEquals("native-1", metadata(supplied).get(13));
        VfsManagedFile changedNative = observe(42, 200, 90, "native-2");
        assertEquals(first.contentVersion(), changedNative.contentVersion());
        assertEquals("native-2", metadata(supplied).get(13));
    }

    @Test
    void eitherSizeOrModificationTimeAdvancesFileVersion() {
        reserve(supplied, "absent.png");
        VfsManagedFile first = observe(42, 200, 100, null);
        VfsManagedFile sizeChanged = observe(43, 200, 100, null);
        assertEquals(first.contentVersion() + 1, sizeChanged.contentVersion());
        VfsManagedFile timeChanged = observe(43, 201, 100, null);
        assertEquals(sizeChanged.contentVersion() + 1, timeChanged.contentVersion());
    }

    @Test
    void directoryObservationHasZeroSizeAndDoesNotAdvanceVersion() {
        VfsManagedFile before = database.transaction(session ->
                catalog.inTransaction(session).registerFileWithId(root, "absent", true, supplied));
        assertThrows(IllegalArgumentException.class, () -> observe(1, 200, 100, "native"));
        assertEquals(before, catalog.findFile(supplied).orElseThrow());
        VfsManagedFile after = observe(0, 200, 100, "native");
        assertEquals(VfsFileStatus.PRESENT, after.status());
        assertEquals(0, after.size());
        assertEquals(200, after.modifiedAt());
        assertEquals(100, after.fileCreatedAt());
        assertEquals(before.contentVersion(), after.contentVersion());
        assertEquals(before.contentVersion(), observe(0, 201, 100, null).contentVersion());
    }

    @Test
    void missingObservationAndRelocationSourcesAreRejected() {
        database.transaction(session -> {
            VfsCatalogTransaction view = catalog.inTransaction(session);
            assertThrows(StorageException.class, () -> view.applyObservation(supplied, 42, 200, 100, null));
            assertThrows(StorageException.class, () -> view.relocateFile(supplied, root, "new.png"));
            return null;
        });
    }

    @Test
    void relocationAcrossRootsPreservesMeasuredAndLogicalFields() throws Exception {
        reserve(supplied, "old.png");
        VfsManagedFile before = observe(42, 200, 100, "native-1");
        catalog.setReadOnly(supplied, true);
        VfsRootId destination = catalog.registerRoot("other", "Other", Files.createDirectory(home.resolve("other")));
        VfsManagedFile after = database.transaction(session ->
                catalog.inTransaction(session).relocateFile(supplied, destination, "new.png"));
        assertTrue(catalog.findFile(root, "old.png").isEmpty());
        assertEquals(after, catalog.findFile(destination, "new.png").orElseThrow());
        assertEquals(destination, after.rootId());
        assertEquals(supplied, after.fileId());
        assertEquals(before.status(), after.status());
        assertEquals(before.size(), after.size());
        assertEquals(before.modifiedAt(), after.modifiedAt());
        assertEquals(before.fileCreatedAt(), after.fileCreatedAt());
        assertEquals(before.contentVersion() + 1, after.contentVersion());
        assertEquals("native-1", metadata(supplied).get(13));
        assertTrue(after.readOnly());
    }

    @Test
    void sameLocationIsUnchangedAndCaseOnlyRelocationIsDistinct() {
        VfsManagedFile before = reserve(supplied, "old.png");
        List<String> unchanged = metadata(supplied);
        assertEquals(before, database.transaction(session ->
                catalog.inTransaction(session).relocateFile(supplied, root, "old.png")));
        assertEquals(unchanged, metadata(supplied));
        VfsManagedFile after = database.transaction(session ->
                catalog.inTransaction(session).relocateFile(supplied, root, "OLD.png"));
        assertEquals("OLD.png", after.relativePath());
        assertEquals(before.contentVersion() + 1, after.contentVersion());
        assertTrue(catalog.findFile(root, "old.png").isEmpty());
        assertEquals(after, catalog.findFile(root, "OLD.png").orElseThrow());
    }

    @Test
    void presentTargetRejectsRelocationWithoutChangingEitherRow() {
        relocationCollision(VfsFileStatus.PRESENT);
    }

    @Test
    void missingTargetRejectsRelocationUntilExplicitlyRemoved() {
        relocationCollision(VfsFileStatus.MISSING);
    }

    private void relocationCollision(VfsFileStatus status) {
        VfsFileId occupant = VfsFileId.of("87654321-4321-4321-8321-cba987654321");
        reserve(supplied, "old.png");
        reserve(occupant, "new.png");
        if (status == VfsFileStatus.PRESENT) {
            database.transaction(session -> catalog.inTransaction(session).applyObservation(occupant, 9, 8, 7, "target"));
        }
        List<String> sourceBefore = metadata(supplied);
        List<String> targetBefore = metadata(occupant);
        database.transaction(session -> {
            assertThrows(StorageException.class, () -> catalog.inTransaction(session).relocateFile(supplied, root, "new.png"));
            return null;
        });
        assertEquals(sourceBefore, metadata(supplied));
        assertEquals(targetBefore, metadata(occupant));
        database.transaction(session -> {
            VfsCatalogTransaction view = catalog.inTransaction(session);
            view.removeFile(occupant);
            view.relocateFile(supplied, root, "new.png");
            return null;
        });
        assertTrue(catalog.findFile(occupant).isEmpty());
        assertEquals(supplied, catalog.findFile(root, "new.png").orElseThrow().fileId());
    }

    @Test
    void relocationRejectsUnknownRootWithoutChangingSource() {
        reserve(supplied, "old.png");
        List<String> before = metadata(supplied);
        assertThrows(StorageException.class, () -> database.transaction(session ->
                catalog.inTransaction(session).relocateFile(supplied, VfsRootId.of("unknown"), "new.png")));
        assertEquals(before, metadata(supplied));
    }

    @Test
    void auxiliaryFailureRollsBackObservationAndRelocationTogether() {
        createAuxiliary();
        reserve(supplied, "old.png");
        catalog.setReadOnly(supplied, true);
        List<String> before = metadata(supplied);
        assertThrows(StorageException.class, () -> database.transaction(session -> {
            VfsCatalogTransaction view = catalog.inTransaction(session);
            view.applyObservation(supplied, 42, 200, 100, "native-1");
            view.relocateFile(supplied, root, "nested/new.png");
            session.update("insert into auxiliary values (?, ?)", supplied.value(), null);
            return null;
        }));
        assertEquals(before, metadata(supplied));
        assertTrue(catalog.findFile(root, "nested/new.png").isEmpty());
        assertEquals(0, auxiliaryCount());
    }

    private VfsManagedFile observe(long size, long modifiedAt, long createdAt, String nativeId) {
        return database.transaction(session ->
                catalog.inTransaction(session).applyObservation(supplied, size, modifiedAt, createdAt, nativeId));
    }

    @Test
    void newMutationsRejectSessionsWithoutAnOwnedWriteTransaction() {
        reserve(supplied, "old.png");
        database.read(session -> {
            assertNewMutationsRejected(() -> catalog.inTransaction(session));
            return null;
        });
        database.write(session -> {
            assertNewMutationsRejected(() -> catalog.inTransaction(session));
            return null;
        });
        DatabaseHandle other = service.open(DatabaseDescriptor.builder("other.test")
                .owner(DatabaseOwner.application("tests")).name("other.test").build());
        other.transaction(session -> {
            assertNewMutationsRejected(() -> catalog.inTransaction(session));
            return null;
        });
        assertEquals("old.png", catalog.findFile(supplied).orElseThrow().relativePath());
    }

    @Test
    void newMutationsRejectAnotherThread() {
        reserve(supplied, "old.png");
        List<String> before = metadata(supplied);
        database.transaction(session -> {
            VfsCatalogTransaction view = catalog.inTransaction(session);
            FutureTask<Boolean> result = new FutureTask<>(() -> {
                assertNewMutationsRejected(() -> view);
                return true;
            });
            Thread thread = new Thread(result, "catalog-mutation-test");
            thread.setDaemon(true);
            thread.start();
            assertTrue(assertDoesNotThrow(() -> result.get(2, TimeUnit.SECONDS)));
            return null;
        });
        assertEquals(before, metadata(supplied));
    }

    @Test
    void newMutationsRejectEndedCallbacksAndClosedCatalog() {
        reserve(supplied, "old.png");
        VfsCatalogTransaction ended = database.transaction(session -> catalog.inTransaction(session));
        database.transaction(session -> {
            assertNewMutationsRejected(() -> ended);
            VfsCatalogTransaction current = catalog.inTransaction(session);
            catalog.close();
            assertNewMutationsRejected(() -> current);
            return null;
        });
    }

    private void assertNewMutationsRejected(java.util.function.Supplier<VfsCatalogTransaction> view) {
        assertThrows(StorageException.class, () -> view.get().applyObservation(supplied, 42, 200, 100, "native-1"));
        assertThrows(StorageException.class, () -> view.get().relocateFile(supplied, root, "new.png"));
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
