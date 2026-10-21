package com.jdesk.frame.storage;

import com.jdesk.frame.storage.dao.EntityDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * A borrowed database handle: reads and writes go through, but {@code close} and {@code dispose} do nothing, so a
 * borrower can't close the database its owner and other holders share, even from its own Disposer parent.
 */
final class BorrowedDatabaseHandle implements DatabaseHandle {

    private static final Logger LOG = LoggerFactory.getLogger(BorrowedDatabaseHandle.class);

    private final DatabaseHandle shared;

    BorrowedDatabaseHandle(DatabaseHandle shared) {
        this.shared = Objects.requireNonNull(shared, "shared");
    }

    @Override
    public String id() {
        return shared.id();
    }

    @Override
    public Path path() {
        return shared.path();
    }

    @Override
    public void migrate(String namespace, List<DatabaseMigration> migrations) {
        shared.migrate(namespace, migrations);
    }

    @Override
    public <T> T read(DatabaseCallback<T> callback) {
        return shared.read(callback);
    }

    @Override
    public <T> T write(DatabaseCallback<T> callback) {
        return shared.write(callback);
    }

    @Override
    public <T> T transaction(TransactionCallback<T> callback) {
        return shared.transaction(callback);
    }

    @Override
    public <T> CompletableFuture<T> readAsync(DatabaseCallback<T> callback) {
        return shared.readAsync(callback);
    }

    @Override
    public <T> CompletableFuture<T> writeAsync(DatabaseCallback<T> callback) {
        return shared.writeAsync(callback);
    }

    @Override
    public <T, ID> EntityDao<T, ID> entityDao(Class<T> entityClass, Class<ID> idClass) {
        return shared.entityDao(entityClass, idClass);
    }

    @Override
    public boolean isDisposed() {
        return shared.isDisposed();
    }

    @Override
    public void close() {
        dispose();
    }

    @Override
    public void dispose() {
        LOG.debug("storage.borrow.closeIgnored database={} (a borrowed handle is closed by its owner)", shared.id());
    }
}
