package com.jdesk.frame.storage;

import com.jdesk.core.io.PathAccessPolicy;
import com.jdesk.frame.storage.dao.EntityDao;
import com.jdesk.frame.storage.dao.SqlEntityDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * A borrowed database handle: reads and writes go through, but {@code close} and {@code dispose} do nothing, so a
 * borrower can't close the database its owner and other holders share, even from its own Disposer parent.
 * The view retains the path policy captured when it was borrowed.
 */
final class BorrowedDatabaseHandle implements DatabaseHandle {

    private static final Logger LOG = LoggerFactory.getLogger(BorrowedDatabaseHandle.class);

    private final DatabaseHandle shared;
    private final PathAccessPolicy pathAccess;

    BorrowedDatabaseHandle(DatabaseHandle shared, PathAccessPolicy pathAccess) {
        this.shared = Objects.requireNonNull(shared, "shared");
        this.pathAccess = Objects.requireNonNull(pathAccess, "pathAccess");
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
        withinPolicy(() -> { shared.migrate(namespace, migrations); return null; });
    }

    @Override
    public <T> T read(DatabaseCallback<T> callback) {
        return withinPolicy(() -> shared.read(callback));
    }

    @Override
    public <T> T write(DatabaseCallback<T> callback) {
        return withinPolicy(() -> shared.write(callback));
    }

    @Override
    public <T> T transaction(TransactionCallback<T> callback) {
        return withinPolicy(() -> shared.transaction(callback));
    }

    @Override
    public <T> CompletableFuture<T> readAsync(DatabaseCallback<T> callback) {
        return withinPolicy(() -> shared.readAsync(callback));
    }

    @Override
    public <T> CompletableFuture<T> writeAsync(DatabaseCallback<T> callback) {
        return withinPolicy(() -> shared.writeAsync(callback));
    }

    @Override
    public <T, ID> EntityDao<T, ID> entityDao(Class<T> entityClass, Class<ID> idClass) {
        PathAccessPolicy access = pathAccess.capture();
        if (!access.requiresExistingRoot()) {
            return shared.entityDao(entityClass, idClass);
        }
        return new SqlEntityDao<>(new BorrowedDatabaseHandle(shared, access), entityClass, idClass);
    }

    private <T> T withinPolicy(Supplier<T> operation) {
        try (var scope = pathAccess.enter()) {
            return operation.get();
        }
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
