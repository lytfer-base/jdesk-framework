package com.jdesk.frame.storage.sqlite;

import com.jdesk.core.app.PathManager;
import com.jdesk.core.disposer.Disposer;
import com.jdesk.core.io.Cancellation;
import com.jdesk.frame.storage.DatabaseDescriptor;
import com.jdesk.frame.storage.DatabaseHandle;
import com.jdesk.frame.storage.DatabaseMigration;
import com.jdesk.frame.storage.DatabaseOptions;
import com.jdesk.frame.storage.DatabaseOwner;
import com.jdesk.frame.storage.DatabaseService;
import com.jdesk.frame.storage.StorageException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteDatabaseServiceTest {

    @TempDir
    Path homeDir;

    @AfterEach
    void clearState() {
        Disposer.clearAll();
        PathManager.resetForTests();
    }

    @Test
    void opensDatabaseUnderApplicationSystemDirectory() {
        PathManager.setHomePathForTests(homeDir);
        DatabaseService service = new SqliteDatabaseService();

        DatabaseHandle database = service.open(DatabaseDescriptor.builder("myapp.records")
                .owner(DatabaseOwner.application("myapp"))
                .name("records")
                .build());

        try {
            Path expected = homeDir.resolve("system")
                    .resolve("databases")
                    .resolve("app")
                    .resolve("myapp")
                    .resolve("records.sqlite")
                    .toAbsolutePath()
                    .normalize();
            assertEquals(expected, database.path());
            assertTrue(Files.isRegularFile(expected));
            assertTrue(service.findOpen("myapp.records").isPresent());
        } finally {
            Disposer.dispose(database);
        }
    }

    @Test
    void rejectsInvalidDatabaseId() {
        PathManager.setHomePathForTests(homeDir);
        DatabaseService service = new SqliteDatabaseService();

        StorageException failure = assertThrows(StorageException.class, () -> service.open(
                DatabaseDescriptor.builder("../escape")
                        .owner(DatabaseOwner.application("myapp"))
                        .name("records")
                        .build()));

        assertTrue(failure.getMessage().contains("Invalid database id"));
    }

    @Test
    void appliesDefaultSqlitePragmasWithoutForeignKeys() {
        PathManager.setHomePathForTests(homeDir);
        DatabaseService service = new SqliteDatabaseService();
        DatabaseHandle database = service.open(DatabaseDescriptor.builder("pragma.check")
                .owner(DatabaseOwner.application("tests"))
                .name("pragma-check")
                .build());

        try {
            String journalMode = database.read(session -> session.queryOne("PRAGMA journal_mode",
                    row -> row.getString("journal_mode")).orElse(""));
            int foreignKeys = database.read(session -> session.queryOne("PRAGMA foreign_keys",
                    row -> row.getInt("foreign_keys")).orElse(-1));
            int busyTimeout = database.read(session -> session.queryOne("PRAGMA busy_timeout",
                    row -> row.getInt("timeout")).orElse(-1));

            assertEquals("wal", journalMode.toLowerCase());
            assertEquals(0, foreignKeys);
            assertEquals(5000, busyTimeout);
        } finally {
            Disposer.dispose(database);
        }
    }

    @Test
    void writeOperationsRunThroughSingleWriterQueue() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseService service = new SqliteDatabaseService();
        DatabaseHandle database = service.open(DatabaseDescriptor.builder("writer.queue")
                .owner(DatabaseOwner.application("tests"))
                .name("writer-queue")
                .build());

        try {
            database.write(session -> {
                session.update("create table entries(id integer primary key, value text)");
                return null;
            });

            CompletableFuture<Integer> first = database.writeAsync(session -> {
                session.update("insert into entries(value) values (?)", "one");
                return 1;
            });
            CompletableFuture<Integer> second = database.writeAsync(session -> {
                session.update("insert into entries(value) values (?)", "two");
                return 2;
            });

            assertEquals(1, first.get(3, TimeUnit.SECONDS));
            assertEquals(2, second.get(3, TimeUnit.SECONDS));
            int count = database.read(session -> session.queryOne("select count(*) as count from entries",
                    row -> row.getInt("count")).orElse(0));
            assertEquals(2, count);
        } finally {
            Disposer.dispose(database);
        }
    }

    @Test
    void disposeRejectsNewDatabaseWork() {
        PathManager.setHomePathForTests(homeDir);
        DatabaseService service = new SqliteDatabaseService();
        DatabaseHandle database = service.open(DatabaseDescriptor.builder("dispose.check")
                .owner(DatabaseOwner.application("tests"))
                .name("dispose-check")
                .build());

        Disposer.dispose(database);

        assertTrue(database.isDisposed());
        assertThrows(StorageException.class, () -> database.read(session -> 1));
        assertFalse(service.findOpen("dispose.check").isPresent());
    }

    @Test
    void runsMigrationsBeforeReturningHandle() {
        PathManager.setHomePathForTests(homeDir);
        DatabaseService service = new SqliteDatabaseService();
        DatabaseMigration migration = DatabaseMigration.of(1, "create-things", session -> {
            session.update("create table things(id text primary key, name text)");
        });

        DatabaseHandle database = service.open(DatabaseDescriptor.builder("migration.check")
                .owner(DatabaseOwner.application("tests"))
                .name("migration-check")
                .migration(migration)
                .build());

        try {
            database.write(session -> {
                session.update("insert into things(id, name) values (?, ?)", "t1", "Thing One");
                return null;
            });
            String name = database.read(session -> session.queryOne("select name from things where id = ?",
                    row -> row.getString("name"), "t1").orElse(""));
            int version = database.read(session -> session.queryOne(
                    "select version from jdesk_schema_version where namespace = ?",
                    row -> row.getInt("version"), "main").orElse(0));
            assertEquals("Thing One", name);
            assertEquals(1, version);
        } finally {
            Disposer.dispose(database);
        }
    }

    /**
     * A component namespace and the host descriptor keep separate version lines: each has its own row in
     * {@code jdesk_schema_version} and runs once, and {@code PRAGMA user_version}, a single integer, follows the host.
     */
    @Test
    void componentNamespaceKeepsItsOwnVersionLine() {
        PathManager.setHomePathForTests(homeDir);
        DatabaseService service = new SqliteDatabaseService();
        DatabaseHandle database = service.open(DatabaseDescriptor.builder("namespace.check")
                .owner(DatabaseOwner.application("tests"))
                .name("namespace-check")
                .migration(DatabaseMigration.of(1, "create-host", session ->
                        session.update("create table host_rows(id text primary key)")))
                .build());

        try {
            List<DatabaseMigration> componentMigrations = List.of(
                    DatabaseMigration.of(1, "create-component", session ->
                            session.update("create table component_rows(id text primary key)")),
                    DatabaseMigration.of(2, "widen-component", session ->
                            session.update("alter table component_rows add column label text")));

            database.migrate("tests.component", componentMigrations);
            database.migrate("tests.component", componentMigrations);

            // A rerun skips applied steps: a second alter would throw on the existing column; the log rows confirm it.
            int componentLogRows = database.read(session -> session.queryOne("""
                    select count(*) as count from jdesk_schema_migration_log where namespace = ?
                    """, row -> row.getInt("count"), "tests.component").orElse(0));
            int userVersion = database.read(session -> session.queryOne(
                    "PRAGMA user_version", row -> row.getInt("user_version")).orElse(-1));

            assertEquals(2, versionOf(database, "tests.component"));
            assertEquals(1, versionOf(database, "main"));
            assertEquals(2, componentLogRows);
            assertEquals(1, userVersion);
        } finally {
            Disposer.dispose(database);
        }
    }

    /** The host namespace is reserved; a component writing to it would make the host skip its migrations. */
    @Test
    void componentCannotMigrateTheHostNamespace() {
        PathManager.setHomePathForTests(homeDir);
        DatabaseService service = new SqliteDatabaseService();
        DatabaseHandle database = service.open(DatabaseDescriptor.builder("namespace.reserved")
                .owner(DatabaseOwner.application("tests"))
                .name("namespace-reserved")
                .build());

        try {
            assertThrows(StorageException.class, () -> database.migrate("main", List.of()));
            assertThrows(StorageException.class, () -> database.migrate("  ", List.of()));
        } finally {
            Disposer.dispose(database);
        }
    }

    private static int versionOf(DatabaseHandle database, String namespace) {
        return database.read(session -> session.queryOne(
                "select version from jdesk_schema_version where namespace = ?",
                row -> row.getInt("version"), namespace).orElse(0));
    }

    @Test
    void failedMigrationRollsBackAndRecordsFailureLog() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseService service = new SqliteDatabaseService();
        DatabaseMigration migration = DatabaseMigration.of(1, "fail-after-create", session -> {
            session.update("create table transient_data(id text primary key)");
            throw new IllegalStateException("boom");
        });

        assertThrows(IllegalStateException.class, () -> service.open(DatabaseDescriptor.builder("migration.failure")
                .owner(DatabaseOwner.application("tests"))
                .name("migration-failure")
                .migration(migration)
                .build()));

        Path databasePath = homeDir.resolve("system")
                .resolve("databases")
                .resolve("app")
                .resolve("tests")
                .resolve("migration-failure.sqlite");

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath)) {
            boolean tableExists;
            try (var statement = connection.prepareStatement("""
                    select name from sqlite_master where type = 'table' and name = ?
                    """)) {
                statement.setString(1, "transient_data");
                try (var resultSet = statement.executeQuery()) {
                    tableExists = resultSet.next();
                }
            }
            int failures;
            try (var statement = connection.prepareStatement("""
                    select count(*) as count from jdesk_schema_migration_log
                    where version = ? and success = 0 and error_message like ?
                    """)) {
                statement.setInt(1, 1);
                statement.setString(2, "%boom%");
                try (var resultSet = statement.executeQuery()) {
                    assertTrue(resultSet.next());
                    failures = resultSet.getInt("count");
                }
            }
            assertFalse(tableExists);
            assertEquals(1, failures);
        }
    }

    @Test
    void opensDatabaseAtExplicitLocation() {
        PathManager.setHomePathForTests(homeDir);
        DatabaseService service = new SqliteDatabaseService();
        Path libraryDir = homeDir.resolve("data").resolve("default").resolve("daily").resolve(".myapp");

        DatabaseHandle database = service.open(DatabaseDescriptor.builder("myapp.library.daily")
                .owner(DatabaseOwner.application("myapp"))
                .name("metadata")
                .location(libraryDir)
                .build());

        try {
            Path expected = libraryDir.resolve("metadata.sqlite").toAbsolutePath().normalize();
            assertEquals(expected, database.path());
            assertTrue(Files.isRegularFile(expected));
        } finally {
            Disposer.dispose(database);
        }
    }

    /**
     * Reopening an id at a different location must fail loudly instead of quietly handing back the file the first
     * caller opened.
     */
    @Test
    void reopeningAnIdAtADifferentLocationFailsInsteadOfSilentlyReusingTheOther() {
        PathManager.setHomePathForTests(homeDir);
        DatabaseService service = new SqliteDatabaseService();
        Path first = homeDir.resolve("data");
        Path second = homeDir.resolve("system").resolve("elsewhere");

        DatabaseHandle database = service.open(descriptorAt("dup.location", first));
        try {
            StorageException failure = assertThrows(StorageException.class,
                    () -> service.open(descriptorAt("dup.location", second)));
            assertTrue(failure.getMessage().contains(first.toString())
                            && failure.getMessage().contains(second.toString()),
                    "the error must name both locations: " + failure.getMessage());
            // The first handle is unaffected: the guard rejects only the new open.
            assertFalse(database.isDisposed(), "a rejected open must not invalidate the handle already open");
        } finally {
            Disposer.dispose(database);
        }
    }

    /** Reopening an id at the <b>same</b> location is normal, as callers share a database, and reuses the handle. */
    @Test
    void reopeningAnIdAtTheSameLocationStillReusesTheHandle() {
        PathManager.setHomePathForTests(homeDir);
        DatabaseService service = new SqliteDatabaseService();
        Path dir = homeDir.resolve("data");

        DatabaseHandle first = service.open(descriptorAt("dup.same", dir));
        try {
            assertSame(first, service.open(descriptorAt("dup.same", dir)));
        } finally {
            Disposer.dispose(first);
        }
    }

    /**
     * When {@code dispose()} returns, the database file must no longer be held open, even if a task on the executor
     * was holding a connection at that moment.
     */
    @Test
    void disposeReturnsOnlyAfterTheFileIsReleased() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseService service = new SqliteDatabaseService();
        DatabaseHandle database = service.open(descriptorAt("dispose.release", homeDir.resolve("data")));
        Path file = database.path();
        CountDownLatch insideTask = new CountDownLatch(1);
        CountDownLatch releaseTask = new CountDownLatch(1);

        database.writeAsync(session -> {
            session.update("create table if not exists holder (x integer)");
            insideTask.countDown();
            try {
                releaseTask.await(5, TimeUnit.SECONDS); // dispose's shutdownNow interrupts this.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return null;
        });
        assertTrue(insideTask.await(5, TimeUnit.SECONDS), "the write task should hold a connection by now");

        Disposer.dispose(database);

        assertTrue(Files.deleteIfExists(file),
                "the file must be released and deletable once dispose returns: " + file);
    }

    /**
     * When dispose drops a queued write that hasn't run, callers waiting on it must get an exception; otherwise the
     * untimed {@code future.get()} in {@code write} would wait forever while the write is silently lost.
     */
    @Test
    void disposeWakesUpCallersWaitingOnQueuedWrites() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseService service = new SqliteDatabaseService();
        DatabaseHandle database = service.open(descriptorAt("dispose.queued", homeDir.resolve("data")));
        CountDownLatch insideTask = new CountDownLatch(1);
        CountDownLatch releaseTask = new CountDownLatch(1);
        database.writeAsync(session -> {          // Occupy the single writer thread.
            insideTask.countDown();
            awaitIgnoringInterrupt(releaseTask);
            return null;
        });
        assertTrue(insideTask.await(5, TimeUnit.SECONDS), "the write task should be inside the executor");

        CompletableFuture<Throwable> queued = new CompletableFuture<>();
        Thread caller = new Thread(() -> {
            try {
                database.write(session -> session.update("create table if not exists queued (x integer)"));
                queued.complete(null);
            } catch (Throwable failure) {
                queued.complete(failure);
            }
        }, "queued-writer");
        caller.setDaemon(true);
        caller.start();
        Thread.sleep(200);    // Let it queue: the writer thread is busy, so this write can't have run.

        Disposer.dispose(database);
        releaseTask.countDown();

        Throwable failure = queued.get(10, TimeUnit.SECONDS);
        assertTrue(failure instanceof StorageException,
                "a caller whose queued write is dropped must get a StorageException, got: " + failure);
    }

    /**
     * A synchronous {@code read} runs on the caller's thread, outside the executor, and dispose must still wait for it;
     * callers rely on the file being released when dispose returns.
     */
    @Test
    void disposeWaitsForASynchronousReadStillRunning() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseService service = new SqliteDatabaseService();
        DatabaseHandle database = service.open(descriptorAt("dispose.syncread", homeDir.resolve("data")));
        CountDownLatch insideRead = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        Thread reader = new Thread(() -> database.read(session -> {
            insideRead.countDown();
            awaitIgnoringInterrupt(releaseRead);
            return null;
        }), "sync-reader");
        reader.setDaemon(true);
        reader.start();
        assertTrue(insideRead.await(5, TimeUnit.SECONDS), "the synchronous read should hold a connection by now");

        CompletableFuture<Void> disposing = CompletableFuture.runAsync(() -> Disposer.dispose(database));
        assertThrows(TimeoutException.class, () -> disposing.get(300, TimeUnit.MILLISECONDS),
                "dispose must not return while a synchronous read runs");

        releaseRead.countDown();
        disposing.get(10, TimeUnit.SECONDS);
        assertTrue(Files.deleteIfExists(database.path()), "the file must be released once dispose returns");
    }

    /** An old handle's close callback must not unregister a new handle opened under the same id during dispose. */
    @Test
    void disposingAnOldHandleDoesNotUnregisterANewOneWithTheSameId() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseService service = new SqliteDatabaseService();
        DatabaseDescriptor descriptor = descriptorAt("handle.swap", homeDir.resolve("data"));
        DatabaseHandle first = service.open(descriptor);
        CountDownLatch insideTask = new CountDownLatch(1);
        CountDownLatch releaseTask = new CountDownLatch(1);
        first.writeAsync(session -> {
            insideTask.countDown();
            awaitIgnoringInterrupt(releaseTask);   // Ignores interrupts to hold dispose in its wait for the executor.
            return null;
        });
        assertTrue(insideTask.await(5, TimeUnit.SECONDS));

        CompletableFuture<Void> disposing = CompletableFuture.runAsync(() -> Disposer.dispose(first));
        long deadline = System.currentTimeMillis() + 5_000;
        while (!first.isDisposed() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(first.isDisposed(), "dispose should have started, so open creates a new handle");
        DatabaseHandle second = service.open(descriptor);

        releaseTask.countDown();
        disposing.get(10, TimeUnit.SECONDS);

        assertTrue(service.findOpen("handle.swap").isPresent(), "the old close must not unregister the new handle");
        assertSame(second, service.findOpen("handle.swap").orElseThrow());
        Disposer.dispose(second);
    }

    /**
     * Reopening after the database file was deleted creates a new handle and reruns the migrations; the cached handle
     * would silently create an empty database at the old path on its next use.
     */
    @Test
    void reopeningAfterTheFileWasDeletedRebuildsItWithItsSchema() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseService service = new SqliteDatabaseService();
        DatabaseDescriptor descriptor = DatabaseDescriptor.builder("file.gone")
                .owner(DatabaseOwner.application("tests"))
                .name("db")
                .location(homeDir.resolve("data"))
                .migration(DatabaseMigration.of(1, "create-notes",
                        session -> session.update("create table notes (id integer)")))
                .build();
        DatabaseHandle first = service.open(descriptor);
        first.write(session -> session.update("insert into notes(id) values (1)"));
        Files.delete(first.path());

        DatabaseHandle second = service.open(descriptor);

        assertNotSame(first, second, "the file is gone, so the cached handle must not be reused");
        long rows = second.read(session ->
                session.queryOne("select count(*) as c from notes", row -> row.getLong("c")).orElseThrow());
        assertEquals(0L, rows, "the rebuilt database must rerun its migrations, not stay an empty shell");
        Disposer.dispose(second);
    }

    /**
     * A borrowed handle can't close the shared database: with one handle per id, a borrower closing it after use would
     * close the owner's database and every holder would get "Database is disposed".
     */
    @Test
    void borrowedHandleCannotCloseTheSharedDatabase() {
        PathManager.setHomePathForTests(homeDir);
        DatabaseService service = new SqliteDatabaseService();
        DatabaseDescriptor descriptor = descriptorAt("borrow.shared", homeDir.resolve("data"));
        DatabaseHandle owned = service.open(descriptor);
        DatabaseHandle borrowed = service.borrow(descriptor);

        borrowed.close();
        Disposer.dispose(borrowed);

        assertFalse(owned.isDisposed(), "a borrower can't close the owner's database");
        assertTrue(service.findOpen("borrow.shared").isPresent());
        borrowed.write(session -> session.update("create table borrowed_ok (x integer)"));

        Disposer.dispose(owned);   // The owner closes it.
        assertTrue(borrowed.isDisposed(), "the borrowed handle is invalid once the owner closes");
    }

    /** A nested synchronous write would make the writer thread wait on itself; it must fail at once, not hang. */
    @Test
    void aNestedWriteFailsInsteadOfDeadlockingTheWriterThread() {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = new SqliteDatabaseService().open(descriptorAt("nested.write", homeDir.resolve("data")));
        try {
            StorageException failure = assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                    assertThrows(StorageException.class,
                            () -> database.write(session -> database.transaction(inner -> 1))));
            assertTrue(failure.getMessage().contains("deadlock"), failure.getMessage());
        } finally {
            Disposer.dispose(database);
        }
    }

    /**
     * A prepared statement runs only its first statement, so SQL holding several must fail; semicolons inside a trigger
     * body or quotes are fine.
     */
    @Test
    void anUpdateWithSeveralStatementsIsRejectedInsteadOfRunningOnlyTheFirst() {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = new SqliteDatabaseService().open(descriptorAt("multi.statement", homeDir.resolve("data")));
        try {
            assertThrows(StorageException.class,
                    () -> database.write(session -> session.update("create table b(x integer); create table c(x integer)")));
            int created = database.read(session -> session.queryList(
                    "select name from sqlite_master where name in ('b', 'c')", row -> row.getString("name")).size());
            assertEquals(0, created, "none of them should run");

            database.write(session -> {
                session.update("create table a(x text);  -- 行尾注释里的分号; 不算");
                session.update("create table b(x integer)");
                session.update("""
                        create trigger a_ad after delete on a begin
                          delete from b where rowid = old.rowid;
                          delete from b where x = 0;
                        end
                        """);
                return session.update("insert into a values ('a;b')");
            });
            assertEquals(List.of("a;b"), database.read(session ->
                    session.queryList("select x from a", row -> row.getString("x"))));
        } finally {
            Disposer.dispose(database);
        }
    }

    /** Duplicate or non-positive versions fail fast instead of a later migration being skipped as already applied. */
    @Test
    void duplicateOrNonPositiveMigrationVersionsFailFast() {
        PathManager.setHomePathForTests(homeDir);
        DatabaseService service = new SqliteDatabaseService();

        StorageException duplicate = assertThrows(StorageException.class, () -> service.open(
                DatabaseDescriptor.builder("migration.duplicate")
                        .owner(DatabaseOwner.application("tests"))
                        .name("duplicate")
                        .location(homeDir.resolve("data"))
                        .migration(DatabaseMigration.of(1, "create-a", session -> session.update("create table a(x)")))
                        .migration(DatabaseMigration.of(1, "create-b", session -> session.update("create table b(x)")))
                        .build()));
        assertTrue(duplicate.getMessage().contains("unique"), duplicate.getMessage());

        assertThrows(StorageException.class, () -> service.open(
                DatabaseDescriptor.builder("migration.zero")
                        .owner(DatabaseOwner.application("tests"))
                        .name("zero")
                        .location(homeDir.resolve("data"))
                        .migration(DatabaseMigration.of(0, "create-a", session -> session.update("create table a(x)")))
                        .build()));
    }

    /** A masked caller defers an interrupt while waiting for a write, gets the result and keeps the flag after. */
    @Test
    void aMaskedCallerWaitsOutAWriteDespiteAnInterrupt() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = new SqliteDatabaseService().open(descriptorAt("masked.write", homeDir.resolve("data")));
        CountDownLatch insideWrite = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        CompletableFuture<Object> outcome = new CompletableFuture<>();
        AtomicBoolean interruptedAfterSection = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                Integer written = Cancellation.uninterruptibly(() -> database.write(session -> {
                    insideWrite.countDown();
                    awaitIgnoringInterrupt(releaseWrite);
                    session.update("create table if not exists slow (x integer)");
                    return 42;
                }));
                interruptedAfterSection.set(Thread.currentThread().isInterrupted());
                outcome.complete(written);
            } catch (Throwable failure) {
                outcome.complete(failure);
            } finally {
                Thread.interrupted();
            }
        }, "masked-caller");
        caller.setDaemon(true);
        try {
            caller.start();
            assertTrue(insideWrite.await(5, TimeUnit.SECONDS), "the write should be running on the writer thread");
            caller.interrupt();
            awaitInterruptConsumed(caller, outcome);
            releaseWrite.countDown();

            assertEquals(42, outcome.get(10, TimeUnit.SECONDS), "a masked caller must still get the result");
            assertTrue(interruptedAfterSection.get(), "the interrupt flag must remain after the section");
        } finally {
            releaseWrite.countDown();
            Disposer.dispose(database);
        }
    }

    /** An interrupted unmasked caller stops waiting, restores the flag and throws StorageException; the write lands. */
    @Test
    void anUnmaskedCallerStopsWaitingWhenInterrupted() throws Exception {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = new SqliteDatabaseService().open(descriptorAt("unmasked.write", homeDir.resolve("data")));
        CountDownLatch insideWrite = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        CompletableFuture<Object> outcome = new CompletableFuture<>();
        AtomicBoolean interruptedAfterFailure = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                outcome.complete(database.write(session -> {
                    insideWrite.countDown();
                    awaitIgnoringInterrupt(releaseWrite);
                    session.update("create table if not exists landed (x integer)");
                    return 42;
                }));
            } catch (Throwable failure) {
                interruptedAfterFailure.set(Thread.currentThread().isInterrupted());
                outcome.complete(failure);
            } finally {
                Thread.interrupted();
            }
        }, "unmasked-caller");
        caller.setDaemon(true);
        try {
            caller.start();
            assertTrue(insideWrite.await(5, TimeUnit.SECONDS), "the write should be running on the writer thread");
            caller.interrupt();

            Object failure = outcome.get(5, TimeUnit.SECONDS);
            assertTrue(failure instanceof StorageException, "an unmasked caller stops waiting, got: " + failure);
            assertTrue(interruptedAfterFailure.get(), "the interrupt flag must be restored");
            releaseWrite.countDown();
            database.write(session -> null); // Queued after that write, so it has run once this returns.
            int landed = database.read(session -> session.queryList(
                    "select name from sqlite_master where name = 'landed'", row -> row.getString("name")).size());
            assertEquals(1, landed, "the write lands even though the caller left");
        } finally {
            releaseWrite.countDown();
            Disposer.dispose(database);
        }
    }

    /** Waits until the caller has taken the interrupt and waits again, or has finished with a result. */
    private static void awaitInterruptConsumed(Thread caller, CompletableFuture<?> outcome) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!outcome.isDone() && System.currentTimeMillis() < deadline) {
            if (!caller.isInterrupted() && caller.getState() == Thread.State.WAITING) {
                return;
            }
            Thread.sleep(5);
        }
    }

    /** Waits for the latch, ignoring interrupts, to hold dispose while it waits for the executor to stop. */
    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        long deadline = System.currentTimeMillis() + 5_000;
        boolean interrupted = false;
        while (latch.getCount() > 0 && System.currentTimeMillis() < deadline) {
            try {
                latch.await(50, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static DatabaseDescriptor descriptorAt(String id, Path location) {
        return DatabaseDescriptor.builder(id)
                .owner(DatabaseOwner.application("tests"))
                .name("db")
                .location(location)
                .build();
    }

    @Test
    void rejectsRelativeLocation() {
        StorageException failure = assertThrows(StorageException.class, () ->
                DatabaseDescriptor.builder("loc.relative")
                        .owner(DatabaseOwner.application("tests"))
                        .name("loc")
                        .location(Path.of("relative", "dir"))
                        .build());
        assertTrue(failure.getMessage().contains("absolute"));
    }
}
