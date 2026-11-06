package com.jdesk.frame.storage.sqlite;

import com.jdesk.core.disposer.Disposer;
import com.jdesk.frame.storage.DatabaseDescriptor;
import com.jdesk.frame.storage.DatabaseHandle;
import com.jdesk.frame.storage.DatabaseMigration;
import com.jdesk.frame.storage.DatabaseOwner;
import com.jdesk.frame.storage.DatabaseTooNewException;
import com.jdesk.frame.storage.StorageException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that a database newer than the program is refused: untouched when its versions can be read, and in any
 * case before a migration runs.
 * Must seed with a rollback journal: in a WAL file the open's pragmas change no bytes, so byte checks prove nothing.
 */
class SqliteDatabaseTooNewTest {

    private static final String ID = "too.new";

    @TempDir Path home;
    private final SqliteDatabaseService service = new SqliteDatabaseService();

    @AfterEach
    void close() {
        Disposer.dispose(service);
        Disposer.clearAll();
    }

    @Test
    void openRejectsNewerMainAndLeavesFileUntouched() throws Exception {
        seed(Map.of("main", 9));
        byte[] before = Files.readAllBytes(database());

        DatabaseTooNewException failure = assertThrows(DatabaseTooNewException.class,
                () -> service.open(descriptor().build()));

        assertEquals(ID, failure.databaseId());
        assertEquals("main", failure.namespace());
        assertEquals(9, failure.databaseVersion());
        assertEquals(1, failure.supportedVersion());
        assertArrayEquals(before, Files.readAllBytes(database()));
    }

    @Test
    void openRejectsDatabaseWrittenByNewerDescriptor() throws Exception {
        service.open(descriptor().migration(noOp(2)).build()).close();
        byte[] before = Files.readAllBytes(database());

        DatabaseTooNewException failure = assertThrows(DatabaseTooNewException.class,
                () -> service.open(descriptor().build()));

        assertEquals(ID, failure.databaseId());
        assertEquals("main", failure.namespace());
        assertEquals(2, failure.databaseVersion());
        assertEquals(1, failure.supportedVersion());
        assertArrayEquals(before, Files.readAllBytes(database()));
    }

    @Test
    void openRejectsNewerDeclaredNamespaceBeforeTouchingDatabase() throws Exception {
        seed(Map.of("main", 1, "ns", 2));
        byte[] before = Files.readAllBytes(database());

        DatabaseTooNewException failure = assertThrows(DatabaseTooNewException.class,
                () -> service.open(descriptor().knownNamespace("ns", 1).build()));

        assertEquals("ns", failure.namespace());
        assertEquals(2, failure.databaseVersion());
        assertEquals(1, failure.supportedVersion());
        assertArrayEquals(before, Files.readAllBytes(database()));
    }

    @Test
    void openIgnoresUndeclaredNamespace() throws Exception {
        seed(Map.of("main", 1, "future.component", 99));

        DatabaseHandle database = service.open(descriptor().build());

        assertFalse(database.isDisposed());
        assertEquals(99, SqliteSchemaVersions.read(database()).get("future.component"));
    }

    @Test
    void migrateRejectsNewerUndeclaredNamespace() throws Exception {
        seed(Map.of("main", 1, "ns", 5));
        DatabaseHandle database = service.open(descriptor().build());

        DatabaseTooNewException failure = assertThrows(DatabaseTooNewException.class,
                () -> database.migrate("ns", List.of(noOp(3))));

        assertEquals(ID, failure.databaseId());
        assertEquals("ns", failure.namespace());
        assertEquals(5, failure.databaseVersion());
        assertEquals(3, failure.supportedVersion());
        assertDoesNotThrow(() -> database.migrate("ns", List.of(noOp(5))));
    }

    @Test
    void withoutMigrationsMainIsNotCompared() throws Exception {
        seed(Map.of("main", 9, "ns", 5));

        DatabaseHandle database = service.open(bareDescriptor().build());

        assertDoesNotThrow(() -> database.migrate("ns", List.of()));
        database.close();
        DatabaseTooNewException failure = assertThrows(DatabaseTooNewException.class,
                () -> service.open(bareDescriptor().knownNamespace("ns", 1).build()));
        assertEquals("ns", failure.namespace());
    }

    @Test
    void failedOpenLeavesNoCachedHandle() throws Exception {
        seed(Map.of("main", 9));
        assertThrows(DatabaseTooNewException.class, () -> service.open(descriptor().build()));
        assertTrue(service.findOpen(ID).isEmpty());

        Files.delete(database());
        seed(Map.of("main", 1));

        assertFalse(service.open(descriptor().build()).isDisposed());
    }

    @Test
    void unreadableVersionFallsThroughToNormalOpen() throws Exception {
        Files.write(database(), "this is not a database file\n".repeat(200).getBytes(StandardCharsets.UTF_8));

        StorageException failure = assertThrows(StorageException.class, () -> service.open(descriptor().build()));

        assertFalse(failure instanceof DatabaseTooNewException);
        assertEquals("Failed to initialize database: " + ID, failure.getMessage());
    }

    @Test
    void tooNewFoundAfterOpenStopsMigrations() throws Exception {
        seedWithHotJournal(Map.of("main", 1, "ns", 2));
        assertThrows(StorageException.class, () -> SqliteSchemaVersions.read(database()),
                "the read-only check must not get to see the versions");
        AtomicBoolean migrated = new AtomicBoolean();

        DatabaseTooNewException failure = assertThrows(DatabaseTooNewException.class, () -> service.open(descriptor()
                .migration(DatabaseMigration.of(2, "pending", session -> migrated.set(true)))
                .knownNamespace("ns", 1)
                .build()));

        assertEquals(ID, failure.databaseId());
        assertEquals("ns", failure.namespace());
        assertEquals(2, failure.databaseVersion());
        assertEquals(1, failure.supportedVersion());
        assertFalse(migrated.get());
        assertEquals(Map.of("main", 1, "ns", 2), SqliteSchemaVersions.read(database()));
        assertTrue(service.findOpen(ID).isEmpty());
    }

    @Test
    void declaredVersionsHoldMainAndKnownNamespaces() {
        assertEquals(Map.of("main", 0), bareDescriptor().build().declaredVersions());
        assertEquals(Map.of("main", 4, "ns", 2),
                bareDescriptor().migration(noOp(4)).migration(noOp(1)).knownNamespace(" ns ", 2).build()
                        .declaredVersions());
    }

    @Test
    void knownNamespaceRejectsReservedBlankOrNonPositive() {
        assertThrows(StorageException.class, () -> bareDescriptor().knownNamespace("main", 1));
        assertThrows(StorageException.class, () -> bareDescriptor().knownNamespace("  ", 1));
        assertThrows(StorageException.class, () -> bareDescriptor().knownNamespace("ns", 0));
    }

    private Path database() {
        return home.resolve("db.sqlite");
    }

    private DatabaseDescriptor.Builder descriptor() {
        return bareDescriptor().migration(noOp(1));
    }

    private DatabaseDescriptor.Builder bareDescriptor() {
        return DatabaseDescriptor.builder(ID)
                .owner(DatabaseOwner.application("tests"))
                .name("db")
                .location(home);
    }

    private static DatabaseMigration noOp(int version) {
        return DatabaseMigration.of(version, "step-" + version, session -> { });
    }

    private void seed(Map<String, Integer> versions) throws SQLException {
        seed(database(), versions);
    }

    /** Leaves {@link #database()} with a hot journal: a read-only connection can't read it, a normal open recovers. */
    private void seedWithHotJournal(Map<String, Integer> versions) throws Exception {
        Path source = home.resolve("source.sqlite");
        seed(source, versions);
        try (Connection writer = DriverManager.getConnection("jdbc:sqlite:" + source);
             Statement statement = writer.createStatement()) {
            // Must stay OFF: otherwise the journal header stays zeroed until its first sync, and it isn't hot yet.
            statement.execute("PRAGMA synchronous=OFF");
            writer.setAutoCommit(false);
            statement.executeUpdate("update jdesk_schema_version set updated_at = 1");
            copy(source, database());
            copy(journal(source), journal(database()));
            writer.rollback();
        }
    }

    private static Path journal(Path database) {
        return database.resolveSibling(database.getFileName() + "-journal");
    }

    private static void copy(Path source, Path target) throws Exception {
        try (InputStream in = Files.newInputStream(source)) {
            Files.copy(in, target);
        }
    }

    private static void seed(Path database, Map<String, Integer> versions) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=delete");
            statement.execute("""
                    create table jdesk_schema_version (
                      namespace text primary key,
                      version integer not null,
                      updated_at integer not null
                    )
                    """);
            for (var entry : versions.entrySet()) {
                statement.executeUpdate("insert into jdesk_schema_version values ('" + entry.getKey() + "', "
                        + entry.getValue() + ", 0)");
            }
        }
    }
}
