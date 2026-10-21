package com.jdesk.frame.storage;

import com.jdesk.core.disposer.Disposable;
import com.jdesk.frame.storage.dao.EntityDao;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A database shared by id: it owns the file, migration state and worker threads for that id, and every caller gets
 * the same object. Only the owner may call {@link #close()} or {@link #dispose()} (see {@link DatabaseService#open});
 * if unsure, use {@link DatabaseService#borrow} for a handle that can't be closed.
 */
public interface DatabaseHandle extends Disposable, AutoCloseable {

    /**
     * Returns the descriptor id used to register this database resource.
     */
    String id();

    /**
     * Returns the resolved SQLite file path managed by this handle.
     */
    Path path();

    /**
     * Runs a component's own migrations on this database under a version line separate from the descriptor's, for a
     * component that keeps its tables in a database it doesn't own; versions already applied are skipped.
     *
     * @param namespace  stable namespace owned by the component; the descriptor's own namespace is rejected
     * @param migrations the component's full migration list, in any order
     */
    void migrate(String namespace, List<DatabaseMigration> migrations);

    /**
     * Runs a read callback in a short-lived {@link DatabaseSession} that is valid only during the callback.
     * Don't call it on the EDT: the SQLite implementation rejects synchronous reads there.
     */
    <T> T read(DatabaseCallback<T> callback);

    /**
     * Runs a write callback through this database's serialized writer queue.
     *
     * <p>Use {@link #transaction(TransactionCallback)} for multi-statement
     * writes that must commit or rollback as one unit.
     */
    <T> T write(DatabaseCallback<T> callback);

    /**
     * Runs a callback inside one transaction on one backend connection.
     *
     * <p>Runtime failures rollback the transaction before the exception is
     * propagated to the caller.
     */
    <T> T transaction(TransactionCallback<T> callback);

    /**
     * Runs {@link #read(DatabaseCallback)} on the database read executor.
     */
    <T> CompletableFuture<T> readAsync(DatabaseCallback<T> callback);

    /**
     * Runs a write callback asynchronously on the serialized writer queue.
     */
    <T> CompletableFuture<T> writeAsync(DatabaseCallback<T> callback);

    /** Creates a generated DAO for a Java record entity annotated from {@code com.jdesk.frame.storage.entity}. */
    <T, ID> EntityDao<T, ID> entityDao(Class<T> entityClass, Class<ID> idClass);

    /**
     * Returns whether the handle has already released its executors and
     * unregistered itself from the owning service.
     */
    boolean isDisposed();

    /**
     * Releases the database resource. Equivalent to disposing this handle.
     */
    @Override
    void close();
}
