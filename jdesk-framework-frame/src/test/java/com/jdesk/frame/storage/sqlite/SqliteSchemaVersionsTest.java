package com.jdesk.frame.storage.sqlite;

import com.jdesk.frame.storage.StorageException;
import com.jdesk.frame.storage.sqlite.SqliteSchemaVersions.TooNew;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies the read-only version lookup against real files, including content that only exists in a -wal file. */
class SqliteSchemaVersionsTest {

    @TempDir Path home;

    @Test
    void readReturnsEveryNamespace() throws Exception {
        Path database = home.resolve("db.sqlite");
        seed(database, Map.of("main", 3, "jdesk.vfs", 2));

        assertEquals(Map.of("main", 3, "jdesk.vfs", 2), SqliteSchemaVersions.read(database));
    }

    @Test
    void readSeesVersionStillInWal() throws Exception {
        Path live = home.resolve("live.sqlite");
        Path mainOnly = Files.createDirectory(home.resolve("main-only")).resolve("db.sqlite");
        Path withWal = Files.createDirectory(home.resolve("with-wal")).resolve("db.sqlite");
        try (Connection writer = DriverManager.getConnection("jdbc:sqlite:" + live)) {
            writeVersionOneThenKeepNineInWal(writer);
            copy(live, mainOnly);
            copy(live, withWal);
            copy(wal(live), wal(withWal));
        }

        assertEquals(Map.of("main", 1), SqliteSchemaVersions.read(mainOnly));
        assertEquals(Map.of("main", 9), SqliteSchemaVersions.read(withWal));
    }

    @Test
    void readReturnsEmptyForMissingFileOrNoTable() throws Exception {
        Path missing = home.resolve("absent").resolve("db.sqlite");
        assertEquals(Map.of(), SqliteSchemaVersions.read(missing));
        assertFalse(Files.exists(home.resolve("absent")));

        Path missingInExistingDirectory = home.resolve("missing.sqlite");
        assertEquals(Map.of(), SqliteSchemaVersions.read(missingInExistingDirectory));
        assertFalse(Files.exists(missingInExistingDirectory));

        Path noTable = home.resolve("plain.sqlite");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + noTable);
             Statement statement = connection.createStatement()) {
            statement.execute("create table notes(id integer primary key)");
        }
        assertEquals(Map.of(), SqliteSchemaVersions.read(noTable));
    }

    @Test
    void readSkipsRowWithoutNamespace() throws Exception {
        Path database = home.resolve("db.sqlite");
        seed(database, Map.of("main", 3));
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("insert into jdesk_schema_version values (null, 9, 0)");
        }

        assertEquals(Map.of("main", 3), SqliteSchemaVersions.read(database));
    }

    @Test
    void readLeavesMainFileUntouched() throws Exception {
        Path live = home.resolve("live.sqlite");
        Path crashed = Files.createDirectory(home.resolve("crashed")).resolve("db.sqlite");
        try (Connection writer = DriverManager.getConnection("jdbc:sqlite:" + live)) {
            writeVersionOneThenKeepNineInWal(writer);
            copy(live, crashed);
            copy(wal(live), wal(crashed));
        }
        byte[] before = Files.readAllBytes(crashed);

        assertEquals(Map.of("main", 9), SqliteSchemaVersions.read(crashed));

        assertArrayEquals(before, Files.readAllBytes(crashed));
        assertEquals(Map.of("main", 9), SqliteSchemaVersions.read(crashed), "the -wal content must survive a read");
    }

    @Test
    void readWorksInChinesePathWithSpaces() throws Exception {
        Path directory = Files.createDirectories(home.resolve("\u6570\u636e \u76ee\u5f55 #1 100%"));
        Path database = directory.resolve("\u9879\u76ee \u5e93.sqlite");
        seed(database, Map.of("main", 4));

        assertEquals(Map.of("main", 4), SqliteSchemaVersions.read(database));
    }

    @Test
    void sqliteAddressMovesOnlyAUncHostIntoThePath() {
        assertEquals("file:////host/share/a%20b.sqlite",
                SqliteSchemaVersions.sqliteAddress(URI.create("file://host/share/a%20b.sqlite")));
        assertEquals("file:///D:/a%20b/c.sqlite",
                SqliteSchemaVersions.sqliteAddress(URI.create("file:///D:/a%20b/c.sqlite")));
    }

    @Test
    void readThrowsStorageExceptionForGarbage() throws Exception {
        Path database = home.resolve("garbage.sqlite");
        Files.write(database, "this is not a database file\n".repeat(200).getBytes(StandardCharsets.UTF_8));

        assertThrows(StorageException.class, () -> SqliteSchemaVersions.read(database));
    }

    @Test
    void findTooNewComparesOnlyDeclaredNamespaces() {
        assertEquals(Optional.empty(),
                SqliteSchemaVersions.findTooNew(Map.of("main", 1, "future", 99), Map.of("main", 1)));
        assertEquals(Optional.empty(), SqliteSchemaVersions.findTooNew(Map.of("main", 1), Map.of("main", 2)));
        assertEquals(Optional.empty(), SqliteSchemaVersions.findTooNew(Map.of(), Map.of("main", 1)));
        assertEquals(Optional.empty(), SqliteSchemaVersions.findTooNew(Map.of("main", 9), Map.of("main", 0)));
        assertEquals(Optional.of(new TooNew("main", 9, 1)),
                SqliteSchemaVersions.findTooNew(Map.of("main", 9), Map.of("main", 1)));
    }

    private static void seed(Path database, Map<String, Integer> versions) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            createVersionTable(statement);
            for (var entry : versions.entrySet()) {
                insertVersion(statement, entry.getKey(), entry.getValue());
            }
        }
    }

    /** Leaves {@code main} at 1 in the main file and at 9 only in the -wal file while the connection stays open. */
    private static void writeVersionOneThenKeepNineInWal(Connection writer) throws SQLException {
        try (Statement statement = writer.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA wal_autocheckpoint=0");
            createVersionTable(statement);
            insertVersion(statement, "main", 1);
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            statement.executeUpdate("update jdesk_schema_version set version = 9 where namespace = 'main'");
        }
    }

    private static void createVersionTable(Statement statement) throws SQLException {
        statement.execute("""
                create table jdesk_schema_version (
                  namespace text primary key,
                  version integer not null,
                  updated_at integer not null
                )
                """);
    }

    private static void insertVersion(Statement statement, String namespace, int version) throws SQLException {
        statement.executeUpdate("insert into jdesk_schema_version values ('" + namespace + "', " + version + ", 0)");
    }

    private static Path wal(Path database) {
        return database.resolveSibling(database.getFileName() + "-wal");
    }

    private static void copy(Path source, Path target) throws Exception {
        try (InputStream in = Files.newInputStream(source)) {
            Files.copy(in, target);
        }
    }
}
