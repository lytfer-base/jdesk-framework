package com.jdesk.frame.history;

import com.jdesk.core.app.ApplicationManager;
import com.jdesk.core.disposer.Disposable;
import com.jdesk.core.history.HistoryPolicy;
import com.jdesk.core.history.HistoryUnavailableReason;
import com.jdesk.core.history.LocalHistoryService;
import com.jdesk.core.history.LocalHistoryStore;
import com.jdesk.core.io.Cancellation;
import com.jdesk.frame.storage.DatabaseDescriptor;
import com.jdesk.frame.storage.DatabaseHandle;
import com.jdesk.frame.storage.DatabaseMigration;
import com.jdesk.frame.storage.DatabaseOwner;
import com.jdesk.frame.storage.DatabaseService;
import com.jdesk.frame.storage.StorageException;
import javax.swing.SwingUtilities;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Application service for local history: opens and closes a {@link SqliteLocalHistoryStore} per scope, and all
 * stores share one capture thread and one maintenance scheduler. A corrupt database is renamed aside and rebuilt;
 * if that fails too, the scope gets an unavailable empty store, so history never blocks its callers.
 */
public final class DefaultLocalHistoryService implements LocalHistoryService, Disposable {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultLocalHistoryService.class);
    static final String DATABASE_NAME = "history";
    static final String GITIGNORE = "*\n";
    private static final Duration FIRST_MAINTENANCE = Duration.ofMinutes(1);
    /**
     * Upper bound on draining queued captures before the stores close at shutdown.
     * Don't raise it: a process killed for overrunning its exit budget never reaches {@code sealAll}.
     */
    private static final long CAPTURE_DRAIN_TIMEOUT_SECONDS = 2;
    /**
     * Upper bound on waiting for a store's own queue before closing it off the EDT.
     * Keep it generous: a large deletion queues many delete marks, and those left when the database closes are lost.
     */
    private static final Duration CLOSE_PENDING_TIMEOUT = Duration.ofSeconds(30);
    /**
     * Upper bound on that wait when a store is closed on the EDT, for example while the application exits.
     * Keep it short: a long wait there can overrun the exit budget and get the whole process killed.
     */
    private static final Duration EDT_PENDING_TIMEOUT = Duration.ofSeconds(1);
    /** SQLite primary result codes meaning the database is broken: corrupt, or not a database file. */
    private static final int SQLITE_CORRUPT = 11;
    private static final int SQLITE_NOTADB = 26;
    private static final Duration MAINTENANCE_PERIOD = Duration.ofHours(6);

    private record Open(LocalHistoryStore store, DatabaseHandle database, ScheduledFuture<?> maintenance) {
    }

    private final Supplier<DatabaseService> databases;
    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler;
    private final Clock clock;
    private final Map<String, Open> stores = new ConcurrentHashMap<>();
    private volatile boolean disposed;

    /** Creates the service with daemon single-thread executors, looking up {@link DatabaseService} when used. */
    public DefaultLocalHistoryService() {
        this(() -> ApplicationManager.getApplication().getService(DatabaseService.class),
                Executors.newSingleThreadExecutor(daemon("history-capture")),
                Executors.newSingleThreadScheduledExecutor(daemon("history-maintenance")),
                Clock.systemDefaultZone());
    }

    DefaultLocalHistoryService(Supplier<DatabaseService> databases, ExecutorService executor,
                               ScheduledExecutorService scheduler, Clock clock) {
        this.databases = Objects.requireNonNull(databases, "databases");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public synchronized LocalHistoryStore openStore(String scopeId, Path historyDir, HistoryPolicy policy) {
        checkUsable();
        Objects.requireNonNull(historyDir, "historyDir");
        Objects.requireNonNull(policy, "policy");
        String id = requireScopeId(scopeId);
        Open existing = stores.get(id);
        if (existing != null) {
            return existing.store();
        }
        Path dir = historyDir.toAbsolutePath().normalize();
        Open opened = open(id, dir, policy);
        stores.put(id, opened);
        return opened.store();
    }

    private Open open(String scopeId, Path dir, HistoryPolicy policy) {
        try {
            Files.createDirectories(dir);
            writeGitignore(dir);
        } catch (IOException | UncheckedIOException e) {
            LOG.warn("history.open.dirFailed scope={} dir={}", scopeId, dir, e);
            return unavailable(scopeId, policy, HistoryUnavailableReason.DIRECTORY_UNAVAILABLE);
        }
        DatabaseHandle database;
        try {
            database = openDatabase(scopeId, dir);
        } catch (StorageException first) {
            if (!looksCorrupt(first)) {
                // A full disk or network I/O error leaves the database intact; quarantining it would hide all history.
                LOG.warn("history.open.unavailable scope={} dir={}", scopeId, dir, first);
                return unavailable(scopeId, policy, HistoryUnavailableReason.TEMPORARILY_UNAVAILABLE);
            }
            LOG.warn("history.open.rebuild scope={} dir={}", scopeId, dir, first);
            quarantineDatabaseFiles(dir);
            try {
                database = openDatabase(scopeId, dir);
            } catch (StorageException second) {
                LOG.warn("history.open.failed scope={} dir={}", scopeId, dir, second);
                return unavailable(scopeId, policy, HistoryUnavailableReason.OPEN_FAILED);
            }
        }
        reportStaleSchema(dir, database);
        LocalHistoryStore store = new SqliteLocalHistoryStore(scopeId, database,
                new HistoryObjectStore(dir.resolve("objects")), policy, executor, clock);
        ScheduledFuture<?> maintenance = scheduler.scheduleWithFixedDelay(() -> maintain(store),
                FIRST_MAINTENANCE.toMillis(), MAINTENANCE_PERIOD.toMillis(), TimeUnit.MILLISECONDS);
        return new Open(store, database, maintenance);
    }

    /**
     * Logs an ERROR if the database predates the {@code lh_activities.params} column; a failed probe is ignored.
     * Keep it read-only: don't rename, rebuild or migrate here.
     */
    private static void reportStaleSchema(Path dir, DatabaseHandle database) {
        try {
            List<String> columns = database.read(session -> session.queryList(
                    "select name from pragma_table_info('lh_activities')", row -> row.getString("name")));
            if (!columns.isEmpty() && !columns.contains("params")) {
                LOG.error("Local history database {} predates the lh_activities.params column; convert it before use",
                        dir.resolve(DATABASE_NAME + ".sqlite"));
            }
        } catch (RuntimeException probeFailed) {
            LOG.debug("history.open.schemaProbeFailed dir={}", dir, probeFailed);
        }
    }

    private static Open unavailable(String scopeId, HistoryPolicy policy, HistoryUnavailableReason reason) {
        return new Open(new NoopHistoryStore(scopeId, policy, reason), null, null);
    }

    private DatabaseHandle openDatabase(String scopeId, Path dir) {
        DatabaseDescriptor.Builder builder = DatabaseDescriptor.builder("jdesk.history." + sanitize(scopeId))
                .owner(DatabaseOwner.application("jdesk"))
                .name(DATABASE_NAME)
                .location(dir);
        for (DatabaseMigration migration : HistorySchema.migrations()) {
            builder.migration(migration);
        }
        return databases.get().open(builder.build());
    }

    /**
     * Returns whether the failure means a broken database: {@code SQLITE_CORRUPT}, {@code SQLITE_NOTADB} or their
     * messages. Don't widen it to environment failures such as a full disk or permissions: that quarantines good data.
     */
    private static boolean looksCorrupt(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql) {
                int primary = sql.getErrorCode() & 0xFF;
                if (primary == SQLITE_CORRUPT || primary == SQLITE_NOTADB) {
                    return true;
                }
            }
            String message = current.getMessage();
            if (message != null && (message.contains("file is not a database")
                    || message.contains("database disk image is malformed"))) {
                return true;
            }
        }
        return false;
    }

    /** Renames the broken database and its WAL and SHM files aside, so the next open starts from scratch. */
    private void quarantineDatabaseFiles(Path dir) {
        String suffix = ".broken-" + clock.millis();
        List<Path> victims = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir, DATABASE_NAME + ".sqlite*")) {
            entries.forEach(victims::add);
        } catch (IOException e) {
            LOG.warn("history.open.quarantineListFailed dir={}", dir, e);
            return;
        }
        for (Path victim : victims) {
            try {
                Files.move(victim, victim.resolveSibling(victim.getFileName() + suffix));
            } catch (IOException e) {
                LOG.warn("history.open.quarantineFailed file={}", victim, e);
            }
        }
    }

    static void writeGitignore(Path dir) {
        Path file = dir.resolve(".gitignore");
        try {
            if (Files.isRegularFile(file) && GITIGNORE.equals(Files.readString(file, StandardCharsets.UTF_8))) {
                return;
            }
            Files.writeString(file, GITIGNORE, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + file, e);
        }
    }

    private void maintain(LocalHistoryStore store) {
        try {
            store.maintain();
        } catch (RuntimeException failure) {
            if (Cancellation.isCancellation(failure)) {
                LOG.debug("history.maintain.cancelled scope={}", store.scopeId());
            } else {
                LOG.warn("history.maintain.failed scope={}", store.scopeId(), failure);
            }
        }
    }

    @Override
    public Optional<LocalHistoryStore> findOpenStore(String scopeId) {
        if (disposed || scopeId == null) {
            return Optional.empty();
        }
        Open open = stores.get(scopeId);
        return open == null ? Optional.empty() : Optional.of(open.store());
    }

    @Override
    public synchronized boolean closeStore(String scopeId) {
        Open open = stores.remove(requireScopeId(scopeId));
        if (open == null) {
            return false;
        }
        close(open);
        return true;
    }

    private void close(Open open) {
        close(open, SwingUtilities.isEventDispatchThread() ? EDT_PENDING_TIMEOUT : CLOSE_PENDING_TIMEOUT);
    }

    /**
     * @param pendingBudget upper bound on waiting for the store's own queue before closing; {@link Duration#ZERO}
     *                      skips the wait when the queue was already drained
     */
    private void close(Open open, Duration pendingBudget) {
        if (open.maintenance() != null) {
            open.maintenance().cancel(false);
        }
        if (!pendingBudget.isZero()) {
            open.store().awaitPending(pendingBudget);
        }
        try {
            open.store().sealAll();
        } catch (RuntimeException failure) {
            LOG.warn("history.close.sealFailed scope={}", open.store().scopeId(), failure);
        }
        if (open.database() != null) {
            open.database().close();
        }
    }

    /** Waits, bounded, for queued captures before databases close; a timeout costs recent versions, never the files. */
    private void drainCaptures() {
        if (executor.isShutdown()) {
            return;
        }
        try {
            executor.submit(() -> { }).get(CAPTURE_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.warn("history.close.drainTimeout seconds={}", CAPTURE_DRAIN_TIMEOUT_SECONDS, e);
        }
    }

    @Override
    public synchronized void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        // Drain once: the queue is shared, and waiting per store would multiply the exit time by the store count.
        drainCaptures();
        for (Open open : List.copyOf(stores.values())) {
            close(open, Duration.ZERO);
        }
        stores.clear();
        scheduler.shutdownNow();
        executor.shutdownNow();
    }

    private void checkUsable() {
        if (disposed) {
            throw new IllegalStateException("LocalHistoryService is disposed");
        }
    }

    private static String requireScopeId(String scopeId) {
        Objects.requireNonNull(scopeId, "scopeId");
        if (scopeId.isBlank()) {
            throw new IllegalArgumentException("scopeId must not be blank");
        }
        return scopeId;
    }

    static String sanitize(String scopeId) {
        StringBuilder out = new StringBuilder(scopeId.length());
        for (char c : scopeId.toCharArray()) {
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-';
            out.append(ok ? c : '_');
        }
        if (out.length() == 0 || !Character.isLetterOrDigit(out.charAt(0))) {
            out.insert(0, 's');
        }
        return out.toString();
    }

    private static ThreadFactory daemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
