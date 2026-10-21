package com.jdesk.frame.storage.sqlite;

import com.jdesk.frame.storage.DatabaseMigration;
import com.jdesk.frame.storage.StorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** When a commit or rollback fails, switching back to auto-commit must not commit the pending writes. */
final class SqliteTransactionsTest {

    @TempDir
    Path dir;

    @Test
    void aFailedCommitLeavesNothingBehind() throws Exception {
        Path file = dir.resolve("commit.sqlite");
        try (Connection real = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            execute(real, "create table t(x integer)");
            Connection flaky = failingOnce(real, "commit");

            SQLException failure = assertThrows(SQLException.class,
                    () -> SqliteTransactions.run(flaky, () -> execute(flaky, "insert into t values (1)")));

            assertEquals("commit failed", failure.getMessage());
        }
        assertEquals(0, count(file, "select count(*) from t"), "writes whose commit failed must not be stored");
    }

    @Test
    void aFailedRollbackKeepsTheOriginalFailureAndCommitsNothing() throws Exception {
        Path file = dir.resolve("rollback.sqlite");
        try (Connection real = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            execute(real, "create table t(x integer)");
            Connection flaky = failingOnce(real, "rollback");

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> SqliteTransactions.run(flaky, () -> {
                        execute(flaky, "insert into t values (1)");
                        throw new IllegalStateException("boom");
                    }));

            assertEquals("rollback failed", failure.getSuppressed()[0].getMessage());
        }
        assertEquals(0, count(file, "select count(*) from t"), "a failed rollback must not commit partial writes");
    }

    @Test
    void aMigrationWhoseCommitFailsIsNotApplied() throws Exception {
        Path file = dir.resolve("migration.sqlite");
        try (Connection real = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            Connection flaky = failingOnce(real, "commit");
            DatabaseMigration migration = DatabaseMigration.of(1, "create-t",
                    session -> session.update("create table t(x integer)"));

            assertThrows(StorageException.class,
                    () -> new SqliteMigrationRunner("main").run(flaky, List.of(migration)));
        }
        assertEquals(0, count(file, "select count(*) from sqlite_master where name = 't'"), "the table must not exist");
        assertEquals(0, count(file, "select count(*) from jdesk_schema_version"), "the version must not advance");
    }

    @Test
    void aMigrationWhoseRollbackFailsIsNotHalfApplied() throws Exception {
        Path file = dir.resolve("half.sqlite");
        try (Connection real = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            Connection flaky = failingOnce(real, "rollback");
            DatabaseMigration migration = DatabaseMigration.of(1, "create-then-fail", session -> {
                session.update("create table t(x integer)");
                throw new IllegalStateException("boom");
            });

            assertThrows(IllegalStateException.class,
                    () -> new SqliteMigrationRunner("main").run(flaky, List.of(migration)));
        }
        assertEquals(0, count(file, "select count(*) from sqlite_master where name = 't'"),
                "logging after a failed rollback must not switch to auto-commit and commit half a migration");
    }

    /** Forwards to the real connection, except that the first call of method throws SQLException instead. */
    private static Connection failingOnce(Connection real, String method) {
        boolean[] failed = {false};
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, invoked, args) -> {
                    if (invoked.getName().equals(method) && !failed[0]) {
                        failed[0] = true;
                        throw new SQLException(method + " failed");
                    }
                    try {
                        return invoked.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private static Void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
        return null;
    }

    private static int count(Path file, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }
}
