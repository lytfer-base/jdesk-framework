package com.jdesk.frame.storage.sqlite;

import com.jdesk.core.disposer.Disposer;
import com.jdesk.core.io.PathAccessPolicy;
import com.jdesk.frame.storage.DatabaseDescriptor;
import com.jdesk.frame.storage.DatabaseHandle;
import com.jdesk.frame.storage.DatabaseMigration;
import com.jdesk.frame.storage.DatabaseOptions;
import com.jdesk.frame.storage.DatabaseOwner;
import com.jdesk.frame.storage.PreMigrationCopyException;
import com.jdesk.frame.storage.StorageException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.ExtendedCommand;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the copy left before a database's first pending migration: what it holds, when none is left, and that a
 * copy that can't be left stops the migration.
 * Must seed with a rollback journal: in a WAL file the open's pragmas change no bytes, so byte checks prove nothing.
 */
class SqlitePreMigrationCopyTest {

    private static final String ID = "copy.test";
    private static final String ORIGINAL = "original";

    @TempDir Path home;
    private final SqliteDatabaseService service = new SqliteDatabaseService();

    @AfterEach
    void close() {
        Disposer.dispose(service);
        Disposer.clearAll();
    }

    @Test
    void pendingMainMigrationLeavesCopyFirst() throws Exception {
        seed(database(), Map.of("main", 1));

        service.open(descriptor(2).build()).close();

        assertEquals(Map.of("main", 1), SqliteSchemaVersions.read(copy()));
        assertEquals(List.of(ORIGINAL), notes(copy()));
        assertEquals(List.of("v2"), notes(database()));
    }

    @Test
    void copyIncludesContentStillInWal() throws Exception {
        Path live = home.resolve("live.sqlite");
        try (Connection writer = DriverManager.getConnection("jdbc:sqlite:" + live);
             Statement statement = writer.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA wal_autocheckpoint=0");
            createTables(statement, Map.of("main", 1));
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            statement.executeUpdate("insert into notes values ('in wal')");
            copy(live, database());
            copy(sibling(live, "-wal"), sibling(database(), "-wal"));
        }
        assertTrue(Files.size(sibling(database(), "-wal")) > 0);

        service.open(descriptor(2).build()).close();

        assertEquals(List.of(ORIGINAL, "in wal"), notes(copy()));
    }

    @Test
    void pendingNamespaceLeavesCopy() throws Exception {
        seed(database(), Map.of("main", 1));
        DatabaseHandle database = service.open(descriptor(1).build());
        assertFalse(Files.exists(copies()), "main has nothing pending");

        database.migrate("ns", List.of(createTable("ns_table")));
        database.close();

        assertEquals(Map.of("main", 1), SqliteSchemaVersions.read(copy()));
        assertFalse(hasTable(copy(), "ns_table"));
        assertEquals(Map.of("main", 1, "ns", 1), SqliteSchemaVersions.read(database()));
    }

    @Test
    void mainThenNamespaceLeavesOneCopyFromBeforeEither() throws Exception {
        seed(database(), Map.of("main", 1));

        DatabaseHandle database = service.open(descriptor(2).build());
        database.migrate("ns", List.of(createTable("ns_table")));
        database.close();

        assertEquals(Map.of("main", 1), SqliteSchemaVersions.read(copy()));
        assertEquals(List.of(ORIGINAL), notes(copy()));
        assertFalse(hasTable(copy(), "ns_table"));
    }

    @Test
    void secondNamespaceKeepsTheCopyFromBeforeTheFirst() throws Exception {
        seed(database(), Map.of("main", 1));

        DatabaseHandle database = service.open(descriptor(1).build());
        database.migrate("ns", List.of(createTable("ns_table")));
        database.migrate("other", List.of(createTable("other_table")));
        database.close();

        assertFalse(hasTable(copy(), "ns_table"));
    }

    @Test
    void noPendingMigrationLeavesNoCopy() throws Exception {
        seed(database(), Map.of("main", 1, "ns", 1));

        DatabaseHandle database = service.open(descriptor(1).build());
        database.migrate("ns", List.of(createTable("ns_table")));
        database.migrate("empty", List.of());
        database.close();

        assertFalse(Files.exists(copies()));
    }

    @Test
    void descriptorWithoutMigrationsHasNothingPending() throws Exception {
        seed(database(), Map.of());

        service.open(descriptor(0).build()).close();

        assertFalse(Files.exists(copies()));
    }

    @Test
    void freshDatabaseLeavesNoCopy() {
        DatabaseHandle database = service.open(descriptor(2).build());
        database.migrate("ns", List.of(createTable("ns_table")));
        database.close();

        assertFalse(Files.exists(copies()));
    }

    @Test
    void emptyFileCountsAsFresh() throws Exception {
        Files.createFile(database());

        DatabaseHandle database = service.open(descriptor(2).build());
        database.migrate("ns", List.of(createTable("ns_table")));
        database.close();

        assertFalse(Files.exists(copies()));
    }

    @Test
    void newCopyReplacesPreviousAndLeavesNoTemp() throws Exception {
        seed(database(), Map.of("main", 1));

        service.open(descriptor(2).build()).close();
        service.open(descriptor(3).build()).close();

        assertEquals(List.of("x.sqlite"), fileNames(copies()));
        assertEquals(Map.of("main", 2), SqliteSchemaVersions.read(copy()));
        assertEquals(List.of("v2"), notes(copy()));
    }

    @Test
    void staleTemporaryFileDoesNotBlockTheCopy() throws Exception {
        seed(database(), Map.of("main", 1));
        Files.createDirectories(copies());
        Files.write(copies().resolve("x.sqlite.tmp"), garbage());

        service.open(descriptor(2).build()).close();

        assertEquals(List.of("x.sqlite"), fileNames(copies()));
        assertEquals(List.of(ORIGINAL), notes(copy()));
    }

    @Test
    void copyFailureStopsMigrationAndCanRetry() throws Exception {
        seed(database(), Map.of("main", 1));
        byte[] before = Files.readAllBytes(database());
        Path blocked = home.resolve("blocked");
        Files.writeString(blocked, "a file where the copy folder should be");

        PreMigrationCopyException failure = assertThrows(PreMigrationCopyException.class,
                () -> service.open(descriptor(2).preMigrationCopyDir(blocked).build()));

        assertEquals(ID, failure.databaseId());
        assertEquals(blocked, failure.copyDir());
        assertNotNull(failure.getCause());
        assertArrayEquals(before, Files.readAllBytes(database()));
        assertEquals(Map.of("main", 1), SqliteSchemaVersions.read(database()));
        assertTrue(service.findOpen(ID).isEmpty());

        service.open(descriptor(2).build()).close();

        assertEquals(2, SqliteSchemaVersions.read(database()).get("main"));
        assertEquals(Map.of("main", 1), SqliteSchemaVersions.read(copy()));
    }

    @Test
    void failedReplaceStopsMigrationAndLeavesNoTemp() throws Exception {
        seed(database(), Map.of("main", 1));
        Files.createDirectories(copy().resolve("in-the-way"));

        assertThrows(PreMigrationCopyException.class, () -> service.open(descriptor(2).build()));

        assertEquals(List.of("x.sqlite"), fileNames(copies()));
        assertEquals(Map.of("main", 1), SqliteSchemaVersions.read(database()));
    }

    @Test
    void namespaceCopyFailureStopsThatMigrationAndCanRetry() throws Exception {
        seed(database(), Map.of("main", 1));
        Files.writeString(copies(), "a file where the copy folder should be");
        DatabaseHandle database = service.open(descriptor(1).build());

        assertThrows(PreMigrationCopyException.class,
                () -> database.migrate("ns", List.of(createTable("ns_table"))));

        assertEquals(Map.of("main", 1), SqliteSchemaVersions.read(database()));
        Files.delete(copies());

        database.migrate("ns", List.of(createTable("ns_table")));

        assertEquals(1, SqliteSchemaVersions.read(database()).get("ns"));
        assertFalse(hasTable(copy(), "ns_table"));
    }

    @Test
    void copyWorksInChinesePathWithSpaces() throws Exception {
        assertCopyWorksIn(home.resolve("\u9879\u76ee \u6570\u636e"), "\u526f\u672c \u6587\u4ef6\u5939");
    }

    @Test
    void copyWorksInPathWithDecomposedName() throws Exception {
        assertCopyWorksIn(home.resolve("\u30ab\u3099\u30fc\u30c6\u3099\u30f3"), "\u30c6\u3099\u30fc\u30bf");
    }

    @Test
    void copyWorksInPathWithUriCharacters() throws Exception {
        assertCopyWorksIn(home.resolve("a#b %41 'c'"), "d#e %42 'f'");
    }

    @Test
    void backupCommandKeepsQuotesInThePath() throws Exception {
        URI target = new URI("file", null, "/a \"b\" 'c'/x.sqlite.tmp", null);

        String parsed = ExtendedCommand.BackupCommand.parse(SqliteDatabaseHandle.backupCommand(target)).destFile;

        assertEquals(target, URI.create(parsed));
    }

    @Test
    void recoverableFileStillLeavesCopyBeforeMigration() throws Exception {
        seedWithHotJournal(Map.of("main", 1));
        assertThrows(StorageException.class, () -> SqliteSchemaVersions.read(database()),
                "the read-only connection must not get to see the versions");

        service.open(descriptor(2).build()).close();

        assertEquals(Map.of("main", 1), SqliteSchemaVersions.read(copy()));
        assertEquals(List.of(ORIGINAL), notes(copy()));
        assertEquals(2, SqliteSchemaVersions.read(database()).get("main"));
    }

    @Test
    void corruptFileIsNotReportedAsCopyFailure() throws Exception {
        Files.write(database(), garbage());

        StorageException failure = assertThrows(StorageException.class, () -> service.open(descriptor(2).build()));

        assertFalse(failure instanceof PreMigrationCopyException);
        assertEquals("Failed to initialize database: " + ID, failure.getMessage());
        assertFalse(Files.exists(copies()));
    }

    @Test
    void existingRootScopeKeepsTheCopyInsideItsRoot() throws Exception {
        Path root = Files.createDirectory(home.resolve("root"));
        seed(root.resolve("x.sqlite"), Map.of("main", 1));
        DatabaseOptions strict = DatabaseOptions.builder().pathAccess(PathAccessPolicy.existingRoot(root)).build();
        Path outside = home.resolve("outside").resolve("copies");

        assertThrows(PreMigrationCopyException.class,
                () -> service.open(descriptorAt(root, outside, 2).options(strict).build()));

        assertFalse(Files.exists(home.resolve("outside")));
        service.open(descriptorAt(root, root.resolve("copies"), 2).options(strict).build()).close();
        assertEquals(Map.of("main", 1), SqliteSchemaVersions.read(root.resolve("copies").resolve("x.sqlite")));
    }

    @Test
    void descriptorHoldsAnAbsoluteCopyFolderOnly() {
        assertTrue(bareDescriptor(home).build().preMigrationCopyDir().isEmpty());
        assertEquals(copies(), descriptor(1).build().preMigrationCopyDir().orElseThrow());
        assertThrows(StorageException.class,
                () -> bareDescriptor(home).preMigrationCopyDir(Path.of("relative")).build());
    }

    @Test
    void descriptorRejectsTheDatabaseFolderAsCopyFolder() {
        assertThrows(StorageException.class, () -> bareDescriptor(home).preMigrationCopyDir(home).build());
        assertThrows(StorageException.class,
                () -> bareDescriptor(home).preMigrationCopyDir(home.resolve("sub").resolve("..")).build());
        assertEquals(home, DatabaseDescriptor.builder(ID).owner(DatabaseOwner.application("tests"))
                .preMigrationCopyDir(home).build().preMigrationCopyDir().orElseThrow(), "no location to compare with");
    }

    private void assertCopyWorksIn(Path folder, String copyFolder) throws Exception {
        Files.createDirectories(folder);
        Path copies = folder.resolve(copyFolder);
        seed(folder.resolve("x.sqlite"), Map.of("main", 1));

        service.open(descriptorAt(folder, copies, 2).build()).close();

        assertEquals(List.of("x.sqlite"), fileNames(copies));
        assertEquals(Map.of("main", 1), SqliteSchemaVersions.read(copies.resolve("x.sqlite")));
        assertEquals(List.of(ORIGINAL), notes(copies.resolve("x.sqlite")));
    }

    private Path database() {
        return home.resolve("x.sqlite");
    }

    private Path copies() {
        return home.resolve("copies");
    }

    private Path copy() {
        return copies().resolve("x.sqlite");
    }

    private DatabaseDescriptor.Builder descriptor(int highest) {
        return descriptorAt(home, copies(), highest);
    }

    private static DatabaseDescriptor.Builder descriptorAt(Path location, Path copies, int highest) {
        DatabaseDescriptor.Builder builder = bareDescriptor(location).preMigrationCopyDir(copies);
        for (int version = 1; version <= highest; version++) {
            builder.migration(step(version));
        }
        return builder;
    }

    private static DatabaseDescriptor.Builder bareDescriptor(Path location) {
        return DatabaseDescriptor.builder(ID)
                .owner(DatabaseOwner.application("tests"))
                .name("x")
                .location(location);
    }

    private static DatabaseMigration step(int version) {
        return DatabaseMigration.of(version, "step-" + version, session -> {
            session.update("create table if not exists notes (body text)");
            session.update("update notes set body = 'v" + version + "'");
        });
    }

    private static DatabaseMigration createTable(String name) {
        return DatabaseMigration.of(1, "create-" + name, session -> session.update("create table if not exists " + name
                + " (id integer)"));
    }

    private static byte[] garbage() {
        return "this is not a database file\n".repeat(200).getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> fileNames(Path directory) throws Exception {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        }
    }

    private static List<String> notes(Path database) throws SQLException {
        List<String> bodies = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select body from notes order by rowid")) {
            while (rows.next()) {
                bodies.add(rows.getString("body"));
            }
        }
        return bodies;
    }

    private static boolean hasTable(Path database, String name) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "select 1 from sqlite_master where type = 'table' and name = '" + name + "'")) {
            return rows.next();
        }
    }

    private static void seed(Path database, Map<String, Integer> versions) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=delete");
            createTables(statement, versions);
        }
    }

    private static void createTables(Statement statement, Map<String, Integer> versions) throws SQLException {
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
        statement.execute("create table notes (body text)");
        statement.executeUpdate("insert into notes values ('" + ORIGINAL + "')");
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
            copy(sibling(source, "-journal"), sibling(database(), "-journal"));
            writer.rollback();
        }
    }

    private static Path sibling(Path database, String suffix) {
        return database.resolveSibling(database.getFileName() + suffix);
    }

    private static void copy(Path source, Path target) throws Exception {
        try (InputStream in = Files.newInputStream(source)) {
            Files.copy(in, target);
        }
    }
}
