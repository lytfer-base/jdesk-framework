package com.jdesk.frame.storage.sqlite;

import com.jdesk.core.io.AtomicFiles;
import com.jdesk.core.io.Cancellation;
import com.jdesk.core.io.PathAccessPolicy;
import com.jdesk.frame.storage.DatabaseCallback;
import com.jdesk.frame.storage.DatabaseDescriptor;
import com.jdesk.frame.storage.DatabaseHandle;
import com.jdesk.frame.storage.DatabaseMigration;
import com.jdesk.frame.storage.DatabaseOptions;
import com.jdesk.frame.storage.DatabaseTooNewException;
import com.jdesk.frame.storage.PreMigrationCopyException;
import com.jdesk.frame.storage.StorageException;
import com.jdesk.frame.storage.TransactionCallback;
import com.jdesk.frame.storage.dao.EntityDao;
import com.jdesk.frame.storage.dao.SqlEntityDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteOpenMode;

import javax.swing.SwingUtilities;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * SQLite database handle for one database file. Each operation opens a short-lived JDBC connection; writes and
 * transactions run one at a time on a single writer thread, async reads on a reader pool, and synchronous reads are
 * rejected on the EDT.
 */
final class SqliteDatabaseHandle implements DatabaseHandle {

    private static final Logger LOG = LoggerFactory.getLogger(SqliteDatabaseHandle.class);

    /** Upper bound on each wait in {@link #dispose()}, so a task that ignores interruption can't hang shutdown. */
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;

    private final DatabaseDescriptor descriptor;
    private final Path path;
    private final Consumer<SqliteDatabaseHandle> closeCallback;
    private final ExecutorService writerExecutor;
    private final ExecutorService readerExecutor;
    /** The writer thread; the thread factory updates it whenever the executor replaces the thread. */
    private volatile Thread writerThread;
    private volatile boolean disposed;
    /** Whether this open needs no further copy: one was left, or the database was new. */
    private volatile boolean copyLeft;
    /** Synchronous reads running on caller threads, which dispose must wait for; guarded by {@link #readsLock}. */
    private int activeReads;
    private final Object readsLock = new Object();

    /**
     * Creates the handle and its worker executors. The database file is opened
     * later by {@link #initialize()}.
     */
    SqliteDatabaseHandle(DatabaseDescriptor descriptor, Path path, Consumer<SqliteDatabaseHandle> closeCallback) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.path = Objects.requireNonNull(path, "path");
        this.closeCallback = Objects.requireNonNull(closeCallback, "closeCallback");
        ThreadFactory writerThreads = threadFactory("jdesk-sqlite-writer-" + descriptor.id());
        this.writerExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = writerThreads.newThread(runnable);
            writerThread = thread;
            return thread;
        });
        this.readerExecutor = Executors.newCachedThreadPool(threadFactory("jdesk-sqlite-reader-" + descriptor.id()));
    }

    /**
     * Applies the descriptor's migrations and prepares its entity tables and indexes; call it once before use.
     *
     * @throws DatabaseTooNewException if a declared namespace is newer than the descriptor; no migration runs
     * @throws PreMigrationCopyException if the copy of a database with pending migrations fails; no migration runs
     */
    void initialize() {
        // Must stay before openConnection(): its pragmas and the migration runner both write to the file.
        readStoredVersions().ifPresent(stored -> {
            rejectIfTooNew(stored, descriptor.declaredVersions());
            leaveCopyBeforeOpening(stored);
        });
        openAndMigrate();
    }

    private void leaveCopyBeforeOpening(Map<String, Integer> stored) {
        if (isFresh()) {
            copyLeft = true;
        } else if (copyDue(stored, SqliteMigrationRunner.MAIN_NAMESPACE, highestMainVersion())) {
            try (Connection connection = SqliteSchemaVersions.openReadOnly(path)) {
                leaveCopy(connection);
            } catch (SQLException e) {
                throw new PreMigrationCopyException(id(), descriptor.preMigrationCopyDir().orElseThrow(), e);
            }
        }
    }

    /** Must answer false when the size can't be read: treating such a file as new would skip its copy. */
    private boolean isFresh() {
        try {
            return !Files.isRegularFile(path) || Files.size(path) == 0;
        } catch (IOException unreadable) {
            return false;
        }
    }

    private int highestMainVersion() {
        return descriptor.declaredVersions().get(SqliteMigrationRunner.MAIN_NAMESPACE);
    }

    private boolean copyDue(Map<String, Integer> stored, String namespace, int highest) {
        return !copyLeft && descriptor.preMigrationCopyDir().isPresent() && stored.getOrDefault(namespace, 0) < highest;
    }

    /** Must replace the previous copy in one move: a backup that fails halfway must not cost the copy already there. */
    private void leaveCopy(Connection source) {
        Path directory = descriptor.preMigrationCopyDir().orElseThrow();
        Path temporary = directory.resolve(path.getFileName() + ".tmp");
        try {
            // Must keep to the policy: an existing-root scope never recreates a folder that went away.
            pathAccess().createDirectories(directory);
            // Must start clean: SQLite would open a file left by a crash as a database and fail on it.
            Files.deleteIfExists(temporary);
            try (Statement statement = source.createStatement()) {
                statement.executeUpdate(backupCommand(temporary.toUri()));
            }
            AtomicFiles.moveIntoPlace(temporary, directory.resolve(path.getFileName()), true);
            copyLeft = true;
        } catch (IOException | SQLException | RuntimeException failure) {
            deleteTemporary(temporary, failure);
            throw new PreMigrationCopyException(id(), directory, failure);
        }
    }

    /** Returns the driver's backup command. Must keep the target a URI: its parser has no escape for {@code "}. */
    static String backupCommand(URI target) {
        return "backup to \"" + SqliteSchemaVersions.sqliteAddress(target) + "\"";
    }

    private static void deleteTemporary(Path temporary, Exception failure) {
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException | RuntimeException suppressed) {
            failure.addSuppressed(suppressed);
        }
    }

    private void openAndMigrate() {
        try (Connection connection = openConnection()) {
            Map<String, Integer> stored = SqliteSchemaVersions.read(connection);
            // Both must stay before the runner: initialize() skips them when it can't read the file.
            rejectIfTooNew(stored, descriptor.declaredVersions());
            if (copyDue(stored, SqliteMigrationRunner.MAIN_NAMESPACE, highestMainVersion())) {
                leaveCopy(connection);
            }
            new SqliteMigrationRunner(SqliteMigrationRunner.MAIN_NAMESPACE)
                    .run(connection, descriptor.migrations());
        } catch (SQLException e) {
            throw new StorageException("Failed to initialize database: " + descriptor.id(), e);
        }
        for (Class<?> entity : descriptor.entities()) {
            entityDaoUnchecked(entity);
        }
    }

    /** Don't merge the two results: empty means the file can't be read, an empty map means it holds no versions. */
    private Optional<Map<String, Integer>> readStoredVersions() {
        try {
            return Optional.of(SqliteSchemaVersions.read(path));
        } catch (StorageException unreadable) {
            // Don't treat an unreadable file as too new or a failed copy: the normal open decides what happens to it.
            LOG.warn("storage.open.versionsUnreadable database={} (continuing with the normal open)",
                    descriptor.id(), unreadable);
            return Optional.empty();
        }
    }

    private void rejectIfTooNew(Map<String, Integer> stored, Map<String, Integer> declared) {
        SqliteSchemaVersions.findTooNew(stored, declared).ifPresent(tooNew -> {
            throw new DatabaseTooNewException(descriptor.id(), tooNew.namespace(), tooNew.databaseVersion(),
                    tooNew.supportedVersion());
        });
    }

    /**
     * Runs a component's migrations on the serialized writer executor, so schema work cannot interleave with
     * concurrent writes. Throws {@link DatabaseTooNewException} if the stored version is above every migration's, and
     * {@link PreMigrationCopyException} if the copy due before them fails.
     */
    @Override
    public void migrate(String namespace, List<DatabaseMigration> migrations) {
        checkUsable();
        String cleaned = Objects.requireNonNull(namespace, "namespace").trim();
        if (cleaned.isEmpty()) {
            throw new StorageException("Migration namespace must not be blank");
        }
        if (SqliteMigrationRunner.MAIN_NAMESPACE.equals(cleaned)) {
            // Otherwise a component's version would overwrite the owner's and its later migrations would be skipped.
            throw new StorageException("Migration namespace is reserved for the owning descriptor: " + cleaned);
        }
        List<DatabaseMigration> declared = List.copyOf(Objects.requireNonNull(migrations, "migrations"));
        rejectNestedWrite();
        await(writerExecutor.submit(contextualCall(() -> {
            try (Connection connection = openConnection()) {
                int highest = declared.stream().mapToInt(DatabaseMigration::version).max().orElse(0);
                Map<String, Integer> stored = SqliteSchemaVersions.read(connection);
                rejectIfTooNew(stored, Map.of(cleaned, highest));
                if (copyDue(stored, cleaned, highest)) {
                    leaveCopy(connection);
                }
                new SqliteMigrationRunner(cleaned).run(connection, declared);
                return null;
            } catch (SQLException e) {
                throw new StorageException("Failed to migrate namespace " + cleaned + " in database: " + id(), e);
            }
        })));
    }

    /**
     * Returns the stable database id from the descriptor.
     */
    @Override
    public String id() {
        return descriptor.id();
    }

    /**
     * Returns the resolved SQLite file path.
     */
    @Override
    public Path path() {
        return path;
    }

    PathAccessPolicy pathAccess() {
        return descriptor.options().pathAccess();
    }

    /**
     * Opens a short-lived connection and runs read work immediately.
     */
    @Override
    public <T> T read(DatabaseCallback<T> callback) {
        warnIfEdt();
        beginRead();
        try (Connection connection = openConnection()) {
            return callback.run(new SqliteDatabaseSession(connection));
        } catch (SQLException e) {
            throw new StorageException("Read failed for database: " + id(), e);
        } finally {
            endRead();
        }
    }

    /** Must count the read and check {@code disposed} under one lock, or dispose may miss a read that just started. */
    private void beginRead() {
        synchronized (readsLock) {
            checkUsable();
            activeReads++;
        }
    }

    private void endRead() {
        synchronized (readsLock) {
            activeReads--;
            if (activeReads == 0) {
                readsLock.notifyAll();
            }
        }
    }

    /**
     * Schedules write work on the single writer executor and waits for it.
     */
    @Override
    public <T> T write(DatabaseCallback<T> callback) {
        checkUsable();
        rejectNestedWrite();
        return await(writerExecutor.submit(contextualCall(() -> executeWrite(callback))));
    }

    /**
     * Runs a callback in one SQLite transaction on the writer executor.
     */
    @Override
    public <T> T transaction(TransactionCallback<T> callback) {
        checkUsable();
        rejectNestedWrite();
        return await(writerExecutor.submit(contextualCall(() -> {
            try (Connection connection = openConnection()) {
                return SqliteTransactions.run(connection,
                        () -> callback.run(new SqliteDatabaseTransaction(connection)));
            } catch (SQLException e) {
                throw new StorageException("Transaction failed for database: " + id(), e);
            }
        })));
    }

    /**
     * Runs a read operation on the reader executor.
     */
    @Override
    public <T> CompletableFuture<T> readAsync(DatabaseCallback<T> callback) {
        checkUsable();
        return CompletableFuture.supplyAsync(contextualSupplier(() -> read(callback)), readerExecutor);
    }

    /**
     * Runs a write operation on the serialized writer executor.
     */
    @Override
    public <T> CompletableFuture<T> writeAsync(DatabaseCallback<T> callback) {
        checkUsable();
        return CompletableFuture.supplyAsync(contextualSupplier(() -> executeWrite(callback)), writerExecutor);
    }

    /**
     * Creates a generated entity DAO backed by this handle.
     */
    @Override
    public <T, ID> EntityDao<T, ID> entityDao(Class<T> entityClass, Class<ID> idClass) {
        checkUsable();
        return new SqlEntityDao<>(this, entityClass, idClass);
    }

    /**
     * Returns whether the handle has stopped accepting new work.
     */
    @Override
    public boolean isDisposed() {
        return disposed;
    }

    /**
     * Closes this handle through the same path as disposer cleanup.
     */
    @Override
    public void close() {
        dispose();
    }

    /**
     * Stops both executors, cancels queued tasks (their callers get a {@link StorageException}) and waits, bounded,
     * for running tasks and synchronous reads, then unregisters the handle; a timeout is only logged.
     * Must not return before those waits: on Windows the file can't be deleted or moved while a connection is open.
     */
    @Override
    public void dispose() {
        synchronized (readsLock) {
            if (disposed) {
                return;
            }
            disposed = true;
        }
        cancelPending(writerExecutor.shutdownNow());
        cancelPending(readerExecutor.shutdownNow());
        awaitTermination(writerExecutor, "writer");
        awaitTermination(readerExecutor, "reader");
        awaitActiveReads();
        closeCallback.accept(this);
    }

    /** Cancels tasks drained by {@code shutdownNow}, which will never run, so callers waiting on them wake up. */
    private static void cancelPending(List<Runnable> pending) {
        for (Runnable task : pending) {
            if (task instanceof Future<?> future) {
                future.cancel(false);
            }
        }
    }

    /** Waits, bounded, for synchronous reads on caller threads; on timeout it logs that the file may stay locked. */
    private void awaitActiveReads() {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(SHUTDOWN_TIMEOUT_SECONDS);
        synchronized (readsLock) {
            while (activeReads > 0) {
                long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
                if (remainingMillis <= 0) {
                    LOG.warn("storage.dispose.readsStillRunning database={} active={} timeoutSeconds={}"
                            + " (the database file may stay locked)", descriptor.id(), activeReads,
                            SHUTDOWN_TIMEOUT_SECONDS);
                    return;
                }
                try {
                    readsLock.wait(remainingMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    LOG.warn("storage.dispose.interrupted database={} executor=read", descriptor.id());
                    return;
                }
            }
        }
    }

    /**
     * Waits, bounded, for one executor to stop.
     * Don't swallow an interrupt: dispose may run on a thread that is itself being cancelled.
     */
    private void awaitTermination(ExecutorService executor, String role) {
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                LOG.warn("storage.dispose.executorStillRunning database={} executor={} timeoutSeconds={}"
                        + " (the database file may stay locked)", descriptor.id(), role, SHUTDOWN_TIMEOUT_SECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOG.warn("storage.dispose.interrupted database={} executor={}", descriptor.id(), role);
        }
    }

    /**
     * Opens one JDBC connection and applies the descriptor's SQLite pragmas.
     */
    Connection openConnection() throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        PathAccessPolicy access = pathAccess().capture();
        try {
            access.checkAccess(path);
        } catch (IOException unavailable) {
            throw new SQLException("Database root is unavailable: " + id(), unavailable);
        }
        if (access.requiresExistingRoot()) {
            config.resetOpenMode(SQLiteOpenMode.CREATE);
        }
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path, config.toProperties());
        try {
            applyPragmas(connection);
        } catch (SQLException | RuntimeException failure) {
            // PRAGMA throws on a corrupt file; close, or the file stays locked and can't even be renamed aside.
            try {
                connection.close();
            } catch (SQLException suppressed) {
                failure.addSuppressed(suppressed);
            }
            throw failure;
        }
        return connection;
    }

    private static <T> Callable<T> contextualCall(Callable<T> operation) {
        PathAccessPolicy access = PathAccessPolicy.current();
        return () -> {
            try (var scope = access.enter()) {
                return operation.call();
            }
        };
    }

    private static <T> Supplier<T> contextualSupplier(Supplier<T> operation) {
        PathAccessPolicy access = PathAccessPolicy.current();
        return () -> {
            try (var scope = access.enter()) {
                return operation.get();
            }
        };
    }

    /**
     * Executes one non-transactional write callback on the writer executor.
     */
    private <T> T executeWrite(DatabaseCallback<T> callback) {
        try (Connection connection = openConnection()) {
            return callback.run(new SqliteDatabaseSession(connection));
        } catch (SQLException e) {
            throw new StorageException("Write failed for database: " + id(), e);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void entityDaoUnchecked(Class<?> entityClass) {
        new SqlEntityDao(this, entityClass, Object.class);
    }

    /** Applies the descriptor's pragmas; several are connection-local, so every connection needs them. */
    private void applyPragmas(Connection connection) throws SQLException {
        DatabaseOptions options = descriptor.options();
        try (Statement statement = connection.createStatement()) {
            if (options.wal()) {
                statement.execute("PRAGMA journal_mode=WAL");
            }
            statement.execute("PRAGMA synchronous=" + options.synchronous().name());
            statement.execute("PRAGMA busy_timeout=" + options.busyTimeout().toMillis());
            statement.execute("PRAGMA foreign_keys=" + (options.foreignKeys() ? "ON" : "OFF"));
        }
    }

    /**
     * Rejects operations after the handle has been disposed.
     */
    private void checkUsable() {
        if (disposed) {
            throw new StorageException("Database is disposed: " + id());
        }
    }

    /** Fails fast on a synchronous write from a write callback, which would deadlock the single writer thread. */
    private void rejectNestedWrite() {
        if (Thread.currentThread() == writerThread) {
            throw new StorageException("Nested synchronous write on the writer thread would deadlock: " + id()
                    + " (use the session passed to the callback instead)");
        }
    }

    /**
     * Prevents blocking synchronous database reads on the Swing event thread.
     */
    private void warnIfEdt() {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new StorageException("Synchronous database read is not allowed on EDT: " + id());
        }
    }

    /**
     * Waits for a task: cancellation by dispose and an interrupted caller (flag restored) throw StorageException, and
     * the task's own runtime exceptions, {@link CancellationException} included, pass through. A submitted write lands
     * even if its caller leaves; inside a masked section ({@link Cancellation#masked()}) an interrupt is deferred.
     */
    private <T> T await(Future<T> future) {
        for (;;) {
            try {
                return future.get();
            } catch (InterruptedException e) {
                if (!Cancellation.masked()) {
                    Thread.currentThread().interrupt();
                    throw new StorageException("Database task interrupted", e);
                }
                Cancellation.deferInterrupt();
            } catch (CancellationException cancelled) {
                throw new StorageException("Database task was cancelled by dispose: " + id(), cancelled);
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (cause instanceof StorageException storageException) {
                    throw storageException;
                }
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new StorageException("Database task failed", cause);
            }
        }
    }

    /**
     * Creates daemon worker threads with stable names for diagnostics.
     */
    private static ThreadFactory threadFactory(String namePrefix) {
        return new ThreadFactory() {
            private final java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger();

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, namePrefix + "-" + counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
    }
}
