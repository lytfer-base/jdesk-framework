package com.jdesk.frame.storage.sqlite;

import com.jdesk.core.disposer.Disposer;
import com.jdesk.core.io.PathAccessPolicy;
import com.jdesk.frame.storage.*;
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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies that temporary strict operations can share an ordinary owner without changing its default policy. */
class SqliteScopedPolicyTest {
    @TempDir Path root;
    private final SqliteDatabaseService service = new SqliteDatabaseService();

    @AfterEach void close() { Disposer.dispose(service); Disposer.clearAll(); }

    @ParameterizedTest
    @ValueSource(strings = {"read", "write", "transaction", "readAsync", "writeAsync", "migrate", "dao"})
    void borrowedViewRetainsThePolicyAfterItsCallingScopeEnds(String operation) throws Exception {
        var descriptor = descriptor(root);
        DatabaseHandle owner = service.open(descriptor);
        DatabaseHandle view;
        EntityDao<Note, Long> dao;
        try (var scope = PathAccessPolicy.existingRoot(root).enter()) {
            view = service.borrow(descriptor);
            dao = view.entityDao(Note.class, Long.class);
            dao.insert(new Note(1L, "original"));
        }
        Path moved = root.resolve("moved.sqlite");
        Files.move(owner.path(), moved);
        Class<? extends Exception> expected = operation.endsWith("Async") ? ExecutionException.class : StorageException.class;
        Exception failure = assertThrows(expected, () -> {
            switch (operation) {
                case "read" -> view.read(s -> 1);
                case "write" -> view.write(s -> 1);
                case "transaction" -> view.transaction(s -> 1);
                case "readAsync" -> view.readAsync(s -> 1).get(5, TimeUnit.SECONDS);
                case "writeAsync" -> view.writeAsync(s -> 1).get(5, TimeUnit.SECONDS);
                case "migrate" -> view.migrate("extra", List.of(DatabaseMigration.of(1, "extra", s -> s.update("create table x(id)"))));
                case "dao" -> dao.findById(1L);
                default -> fail(operation);
            }
        });
        if (failure instanceof ExecutionException) assertInstanceOf(StorageException.class, failure.getCause());
        assertFalse(Files.exists(owner.path()), operation);
        view.close();
        assertFalse(owner.isDisposed());
        assertSame(owner, service.findOpen(owner.id()).orElseThrow());
        Files.move(moved, owner.path());
        assertEquals("original", dao.findById(1L).orElseThrow().body());
    }

    @Test
    void ambientPolicyAlsoReachesTheCachedOwnersWriterAndDoesNotLeakToLaterOperations() throws Exception {
        DatabaseHandle owner = service.open(descriptor(root));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        CompletableFuture<PathAccessPolicy> queued;
        var policy = PathAccessPolicy.existingRoot(root);
        try (var scope = policy.enter()) {
            queued = owner.writeAsync(s -> {
                entered.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                return PathAccessPolicy.current();
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
        assertEquals(policy, queued.get(5, TimeUnit.SECONDS));
        assertEquals(PathAccessPolicy.createMissing(), owner.writeAsync(s -> PathAccessPolicy.current()).get(5, TimeUnit.SECONDS));
        assertEquals(PathAccessPolicy.createMissing(), PathAccessPolicy.current());
    }

    @Test
    void strictScopedOpenDoesNotCreateAMissingDatabaseOrChangeAnOrdinaryOwner() throws Exception {
        Path absent = root.resolve("absent");
        try (var scope = PathAccessPolicy.existingRoot(root).enter()) {
            assertThrows(StorageException.class, () -> service.borrow(descriptor(absent)));
            assertFalse(Files.exists(absent));
        }
        DatabaseHandle ordinary = service.open(descriptor(absent));
        assertTrue(Files.isRegularFile(ordinary.path()));
        try (var scope = PathAccessPolicy.existingRoot(root).enter()) {
            assertSame(ordinary, service.open(descriptor(absent)));
        }
        assertSame(ordinary, service.open(descriptor(absent)));
        Files.delete(ordinary.path());
        DatabaseHandle rebuilt = service.open(descriptor(absent));
        assertTrue(Files.isRegularFile(rebuilt.path()));
        assertNotSame(ordinary, rebuilt);
    }

    @Test
    void strictBorrowCannotDiscardAnOrdinaryOwnerWhenItsFileDisappears() throws Exception {
        var descriptor = descriptor(root);
        DatabaseHandle owner = service.open(descriptor);
        Path displaced = root.resolve("displaced.sqlite");
        Files.move(owner.path(), displaced);
        try (var scope = PathAccessPolicy.existingRoot(root).enter()) {
            assertThrows(StorageException.class, () -> service.borrow(descriptor));
        }
        assertFalse(owner.isDisposed());
        assertSame(owner, service.findOpen(owner.id()).orElseThrow());
        assertFalse(Files.exists(owner.path()));
        Files.move(displaced, owner.path());
        assertSame(owner, service.open(descriptor));
    }

    @Test
    void scopedFirstBorrowCreatesAnOrdinaryOwnerThatCanLaterBeOpenedNormally() throws Exception {
        var descriptor = descriptor(root);
        service.open(descriptor).close();
        assertTrue(service.findOpen(descriptor.id()).isEmpty());
        DatabaseHandle view;
        DatabaseHandle owner;
        try (var scope = PathAccessPolicy.existingRoot(root).enter()) {
            view = service.borrow(descriptor);
            owner = service.findOpen(descriptor.id()).orElseThrow();
            view.write(s -> s.update("create table first_borrow(id integer)"));
        }
        assertSame(owner, service.open(descriptor));
        Path displaced = root.resolve("displaced.sqlite");
        Files.move(owner.path(), displaced);
        assertThrows(StorageException.class, () -> view.write(s -> 1));
        assertFalse(Files.exists(owner.path()));
        owner.write(s -> s.update("create table ordinary_after_scope(id integer)"));
        assertTrue(Files.isRegularFile(owner.path()), "ordinary owner keeps its original creation policy");
        assertFalse(owner.isDisposed());
    }

    @Test
    void anOldDaoOnTheOrdinaryOwnerObeysTheCurrentScopeForReadsAndWrites() throws Exception {
        DatabaseHandle owner = service.open(descriptor(root));
        EntityDao<Note, Long> oldDao = owner.entityDao(Note.class, Long.class);
        oldDao.insert(new Note(1L, "original"));
        Path displaced = root.resolve("displaced.sqlite");
        Files.move(owner.path(), displaced);
        try (var scope = PathAccessPolicy.existingRoot(root).enter()) {
            assertThrows(StorageException.class, () -> oldDao.findById(1L));
            assertThrows(StorageException.class, () -> oldDao.update(new Note(1L, "late")));
        }
        assertFalse(Files.exists(owner.path()));
        Files.move(displaced, owner.path());
        oldDao.update(new Note(1L, "normal"));
        assertEquals("normal", oldDao.findById(1L).orElseThrow().body());
    }

    private static DatabaseDescriptor descriptor(Path location) {
        return DatabaseDescriptor.builder("scoped.test").owner(DatabaseOwner.application("tests"))
                .name("database").location(location).build();
    }

    @Table("notes")
    record Note(@Id @Column("id") Long id, @Column("body") String body) { }
}
