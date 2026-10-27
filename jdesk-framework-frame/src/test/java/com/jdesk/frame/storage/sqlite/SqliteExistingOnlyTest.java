package com.jdesk.frame.storage.sqlite;

import com.jdesk.core.app.PathManager;
import com.jdesk.core.io.PathAccessPolicy;
import com.jdesk.core.disposer.Disposer;
import com.jdesk.frame.storage.DatabaseDescriptor;
import com.jdesk.frame.storage.DatabaseHandle;
import com.jdesk.frame.storage.DatabaseMigration;
import com.jdesk.frame.storage.DatabaseOptions;
import com.jdesk.frame.storage.DatabaseOwner;
import com.jdesk.frame.storage.StorageException;
import com.jdesk.frame.storage.dao.EntityDao;
import com.jdesk.frame.storage.entity.Column;
import com.jdesk.frame.storage.entity.Id;
import com.jdesk.frame.storage.entity.Table;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;



import static org.junit.jupiter.api.Assertions.*;

/** Verifies non-creating connections against real files and all managed operation paths. */
class SqliteExistingOnlyTest {
    @TempDir Path home;
    private final SqliteDatabaseService service = new SqliteDatabaseService();

    @AfterEach
    void close() {
        Disposer.dispose(service);
        PathManager.resetForTests();
        Disposer.clearAll();
    }

    @Test
    void missingParentsAreNotCreated() {
        Path location = home.resolve("absent/child");
        assertThrows(StorageException.class, () -> service.open(descriptor(location, true)));
        assertFalse(Files.exists(home.resolve("absent")));
        assertTrue(service.findOpen("existing.test").isEmpty());
    }

    @Test
    void missingFileInAnExistingParentIsNotCreated() throws Exception {
        Path location = Files.createDirectory(home.resolve("present"));
        assertThrows(StorageException.class, () -> service.open(descriptor(location, true)));
        try (var children = Files.list(location)) {
            assertEquals(0, children.count());
        }
        assertTrue(service.findOpen("existing.test").isEmpty());
    }

    @Test
    void existingOnlyRequiresAnExplicitLocationBeforeResolvingApplicationPaths() {
        PathManager.setHomePathForTests(home);
        var descriptor = DatabaseDescriptor.builder("existing.test")
                .owner(DatabaseOwner.application("test"))
                .options(DatabaseOptions.builder().pathAccess(PathAccessPolicy.existingRoot(home)).build()).build();
        assertThrows(StorageException.class, () -> service.open(descriptor));
        assertFalse(Files.exists(home.resolve("system")));
    }

    @Test
    void existingDatabaseRemainsWritableAndBorrowingCannotCloseItsOwner() {
        Path location = home.resolve("present");
        DatabaseHandle database = seedAndOpenExisting(location);
        database.transaction(tx -> tx.update("insert into notes values (2, 'second')"));
        DatabaseHandle borrowed = service.borrow(descriptor(location, true));
        borrowed.close();
        assertSame(database, service.open(descriptor(location, true)));
        assertFalse(database.isDisposed());
        long count = database.read(s -> s.queryOne("select count(*) as n from notes",
                row -> row.getLong("n")).orElseThrow());
        assertEquals(2L, count);
    }

    @ParameterizedTest
    @ValueSource(strings = {"connection", "initialize", "read", "write", "transaction", "readAsync", "writeAsync",
            "migrate", "entityCreate", "entityRead", "entityWrite"})
    void everyNewConnectionRefusesToRecreateAFileRemovedAfterOpening(String operation) throws Exception {
        DatabaseHandle database = seedAndOpenExisting(home.resolve("present"));
        EntityDao<Note, Long> dao = database.entityDao(Note.class, Long.class);
        Path displaced = database.path().resolveSibling("displaced.sqlite");
        Files.move(database.path(), displaced);
        AtomicBoolean callback = new AtomicBoolean();

        if (operation.equals("connection")) {
            assertThrows(SQLException.class, () -> {
                try (var connection = ((SqliteDatabaseHandle) database).openConnection()) {
                    callback.set(true);
                }
            });
        } else if (operation.equals("readAsync") || operation.equals("writeAsync")) {
            var future = operation.equals("readAsync")
                    ? database.readAsync(s -> { callback.set(true); return 1; })
                    : database.writeAsync(s -> { callback.set(true); return 1; });
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> future.get(5, TimeUnit.SECONDS));
            assertInstanceOf(StorageException.class, failure.getCause());
        } else {
            assertThrows(StorageException.class, () -> {
                switch (operation) {
                    case "initialize" -> ((SqliteDatabaseHandle) database).initialize();
                    case "read" -> database.read(s -> { callback.set(true); return 1; });
                    case "write" -> database.write(s -> { callback.set(true); return 1; });
                    case "transaction" -> database.transaction(s -> { callback.set(true); return 1; });
                    case "migrate" -> database.migrate("extension", List.of(DatabaseMigration.of(1,
                            "add-field", s -> { callback.set(true); s.update("alter table notes add column extra text"); })));
                    case "entityCreate" -> database.entityDao(Note.class, Long.class);
                    case "entityRead" -> dao.findById(1L);
                    case "entityWrite" -> dao.insert(new Note(2L, "late"));
                    default -> fail("Unknown operation: " + operation);
                }
            });
        }
        assertFalse(callback.get(), "callbacks must not run against a replacement database");
        assertFalse(Files.exists(database.path()), operation + " recreated the database");
        assertTrue(Files.isRegularFile(displaced));
        Files.move(displaced, database.path());
        assertEquals("original", dao.findById(1L).orElseThrow().body());
    }

    @Test
    void movedRootIsNotRecreatedAndCachedReopenDoesNotDisposeTheOwner() throws Exception {
        Path location = home.resolve("present");
        DatabaseHandle database = seedAndOpenExisting(location);
        Files.move(location, home.resolve("moved"));
        assertThrows(StorageException.class, () -> service.open(descriptor(location, true)));
        assertThrows(StorageException.class, () -> database.write(s -> 1));
        assertFalse(Files.exists(location));
        assertFalse(database.isDisposed());
        assertSame(database, service.findOpen(database.id()).orElseThrow());
        Files.move(home.resolve("moved"), location);
        assertEquals("original", database.read(s -> s.queryOne("select body from notes where id=1",
                row -> row.getString("body")).orElseThrow()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void incompatiblePolicyCannotReuseOrDisposeAnOpenHandleEvenWhenItsFileDisappears(boolean strictFirst)
            throws Exception {
        Path location = home.resolve("present");
        DatabaseHandle database = strictFirst ? seedAndOpenExisting(location)
                : service.open(descriptor(location, false));
        var incompatible = descriptor(location, strictFirst ? false : true);
        assertThrows(StorageException.class, () -> service.open(incompatible));
        assertFalse(database.isDisposed());
        assertSame(database, service.findOpen(database.id()).orElseThrow());
        Path displaced = database.path().resolveSibling("displaced.sqlite");
        Files.move(database.path(), displaced);
        assertThrows(StorageException.class, () -> service.open(incompatible));
        assertFalse(database.isDisposed());
        assertFalse(Files.exists(database.path()));
        assertSame(database, service.findOpen(database.id()).orElseThrow());
        Files.move(displaced, database.path());
        int value = database.read(s -> s.queryOne("select 1 as n", row -> row.getInt("n")).orElseThrow());
        assertEquals(1, value);
    }

    @Test
    void aDifferentLocationCannotCloseAnOwnerWhoseFileIsTemporarilyAbsent() throws Exception {
        DatabaseHandle database = seedAndOpenExisting(home.resolve("present"));
        Files.move(database.path(), home.resolve("displaced.sqlite"));
        assertThrows(StorageException.class,
                () -> service.open(descriptor(home.resolve("other"), true)));
        assertFalse(database.isDisposed());
        assertSame(database, service.findOpen(database.id()).orElseThrow());
        assertFalse(Files.exists(home.resolve("other")));
    }

    @Test
    void failedInitializationDoesNotRegisterAHandleAndCanBeRetried() {
        Path location = home.resolve("present");
        DatabaseHandle original = service.open(descriptor(location, false));
        original.close();
        var invalid = DatabaseDescriptor.builder("existing.test").owner(DatabaseOwner.application("test"))
                .name("database").location(location)
                .options(DatabaseOptions.builder().pathAccess(PathAccessPolicy.existingRoot(home)).build())
                .migration(DatabaseMigration.of(2, "invalid", s -> s.update("invalid sql"))).build();
        assertThrows(StorageException.class, () -> service.open(invalid));
        assertTrue(service.findOpen("existing.test").isEmpty());
        assertNotNull(service.open(descriptor(location, true)));
    }

    @Test
    void failedEntityInitializationStopsTheWriterStartedByAnEarlierEntity() {
        Path location = home.resolve("present");
        DatabaseHandle original = service.open(descriptor(location, false));
        original.close();
        String id = "initialization.cleanup";
        var invalid = DatabaseDescriptor.builder(id).owner(DatabaseOwner.application("test"))
                .name("database").location(location)
                .options(DatabaseOptions.builder().pathAccess(PathAccessPolicy.existingRoot(home)).build())
                .entity(Note.class).entity(InvalidEntity.class).build();
        StorageException failure = assertThrows(StorageException.class, () -> service.open(invalid));
        assertTrue(failure.getMessage().contains("@Id"), failure.getMessage());
        assertTrue(service.findOpen(id).isEmpty());
        assertFalse(Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.isAlive() && thread.getName().startsWith("jdesk-sqlite-writer-" + id)),
                "failed initialization must release the writer even before its handle is registered");
    }

    @Test
    void creationRemainsTheDefaultAndNullModeIsRejected() {
        assertEquals(PathAccessPolicy.createMissing(), DatabaseOptions.defaults().pathAccess());
        assertThrows(StorageException.class, () -> DatabaseOptions.builder().pathAccess(null).build());
        DatabaseHandle database = service.open(descriptor(home.resolve("new/parent"), false));
        assertTrue(Files.isRegularFile(database.path()));
    }

    private DatabaseHandle seedAndOpenExisting(Path location) {
        DatabaseHandle created = service.open(descriptor(location, false));
        created.write(s -> s.update("insert into notes values (1, 'original')"));
        created.close();
        return service.open(descriptor(location, true));
    }

    private static DatabaseDescriptor descriptor(Path location, boolean strict) {
        return DatabaseDescriptor.builder("existing.test").owner(DatabaseOwner.application("test"))
                .name("database").location(location).options(DatabaseOptions.builder().pathAccess(strict ? PathAccessPolicy.existingRoot(location) : PathAccessPolicy.createMissing()).build())
                .migration(DatabaseMigration.of(1, "notes", s -> s.update("create table notes(id integer primary key, body text)")))
                .build();
    }

    @Table("notes")
    record Note(@Id @Column("id") Long id, @Column("body") String body) { }

    @Table("invalid_entity")
    record InvalidEntity(@Column("body") String body) { }
}
