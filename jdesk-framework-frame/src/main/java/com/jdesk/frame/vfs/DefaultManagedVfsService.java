package com.jdesk.frame.vfs;

import com.jdesk.core.disposer.Disposable;
import com.jdesk.core.app.Application;
import com.jdesk.core.app.ApplicationManager;
import com.jdesk.core.disposer.Disposer;
import com.jdesk.core.history.HistorySource;
import com.jdesk.core.history.LocalHistoryService;
import com.jdesk.core.history.LocalHistoryStore;
import com.jdesk.core.io.Cancellation;
import com.jdesk.core.io.FileLinks;
import com.jdesk.core.vfs.ManagedVfsChangePublisher;
import com.jdesk.core.vfs.ManagedVfsService;
import com.jdesk.core.vfs.ScanFilter;
import com.jdesk.core.vfs.VfsCatalog;
import com.jdesk.core.vfs.VfsCatalogBackend;
import com.jdesk.core.vfs.VfsFileId;
import com.jdesk.core.vfs.VfsFileStatus;
import com.jdesk.core.vfs.VfsManagedFile;
import com.jdesk.core.vfs.VfsRootId;
import com.jdesk.core.vfs.VfsScanResult;
import com.jdesk.core.vfs.VfsWatchListener;
import com.jdesk.core.vfs.VfsWatcher;
import com.jdesk.core.vfs.VfsWatcherFactory;
import com.jdesk.frame.storage.DatabaseHandle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link ManagedVfsService}: catalogs open lazily per scope, closing a scope releases its catalog and nothing
 * else, and the caller keeps control of the database behind each catalog. All scopes share one lazily created
 * directory watcher.
 */
public final class DefaultManagedVfsService implements ManagedVfsService, Disposable {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultManagedVfsService.class);

    private final ConcurrentMap<String, Entry> catalogs = new ConcurrentHashMap<>();
    private final Map<String, Registration> registrations = new ConcurrentHashMap<>();
    /** Registrations by root for event dispatch, like IntelliJ's WatchRootsManager; see {@link #findRegistration}. */
    private final Map<Path, Registration> byRoot = new ConcurrentHashMap<>();
    /** The lazily created watcher shared by every scope's root, so the thread count doesn't grow with scopes. */
    private volatile VfsWatcher globalWatcher;
    private final VfsWatcherFactory watcherFactory;
    private final BiConsumer<String, VfsScanResult> publish;
    /** Looks up a scope's local history store on use; empty for a scope without history, which then skips it. */
    private final Function<String, Optional<LocalHistoryStore>> historyLookup;
    private volatile boolean disposed;

    /** A scope's watch registration; {@code order} comes from the scope's {@link Entry}. */
    private record Registration(String scopeId, VfsRootId rootId, Path root, ScanFilter filter, VfsCatalog catalog,
                                Object order) {
    }

    /**
     * A catalog with the database handle behind it, kept to tell whether the catalog is still alive. Every
     * reconcile-and-publish path holds {@code order} throughout, so events are published in reconcile order.
     */
    private record Entry(VfsCatalog catalog, DatabaseHandle database, Object order) {
    }

    /** Creates the application service with the directory watcher and the message bus publisher. */
    public DefaultManagedVfsService() {
        this(new DirectoryWatcherVfsWatcherFactory(), new ManagedVfsChangePublisher()::publish,
                DefaultManagedVfsService::lookupHistory);
    }

    /** For tests: injects the watcher factory and a publish sink that needs no application or message bus. */
    DefaultManagedVfsService(VfsWatcherFactory watcherFactory, BiConsumer<String, VfsScanResult> publish) {
        this(watcherFactory, publish, scopeId -> Optional.empty());
    }

    /** For tests: also injects the history store lookup. */
    DefaultManagedVfsService(VfsWatcherFactory watcherFactory, BiConsumer<String, VfsScanResult> publish,
                             Function<String, Optional<LocalHistoryStore>> historyLookup) {
        this.watcherFactory = Objects.requireNonNull(watcherFactory, "watcherFactory");
        this.publish = Objects.requireNonNull(publish, "publish");
        this.historyLookup = Objects.requireNonNull(historyLookup, "historyLookup");
    }

    /**
     * Opens or returns the catalog for one scope; a cached catalog whose database handle was disposed is rebuilt on
     * the supplied backend.
     */
    @Override
    public VfsCatalog openCatalog(String scopeId, VfsCatalogBackend catalogBackend, Locale locale) {
        checkUsable();
        String normalizedScopeId = requireScopeId(scopeId);
        DatabaseVfsCatalogBackend backend = requireDatabaseBackend(catalogBackend);
        return catalogs.compute(normalizedScopeId, (id, existing) -> {
            if (existing != null && !existing.database().isDisposed()) {
                return existing;
            }
            if (existing != null) {
                existing.catalog().close();
            }
            return new Entry(new SqliteVfsCatalog(id, backend.database(), locale), backend.database(), new Object());
        }).catalog();
    }

    /**
     * Looks up a currently open catalog without creating it.
     *
     * <p>A catalog whose database handle is gone counts as not open.
     */
    @Override
    public Optional<VfsCatalog> findOpenCatalog(String scopeId) {
        checkUsable();
        Entry entry = catalogs.get(requireScopeId(scopeId));
        return entry == null || entry.database().isDisposed()
                ? Optional.empty()
                : Optional.of(entry.catalog());
    }

    /**
     * Closes a scoped catalog and removes it from the service registry.
     */
    @Override
    public boolean closeCatalog(String scopeId) {
        checkUsable();
        Entry entry = catalogs.remove(requireScopeId(scopeId));
        if (entry == null) {
            return false;
        }
        entry.catalog().close();
        return true;
    }

    /**
     * Starts watching a scope's root. Synchronized with {@link #unwatch}, which may run concurrently on the EDT: the
     * root set must be computed and handed over under one lock, or a root can silently stop being watched.
     * Holding the lock is cheap: {@link VfsWatcher#setRoots} only stores the target and rebuilds asynchronously.
     */
    @Override
    public synchronized void watch(String scopeId, VfsRootId rootId, Path root, ScanFilter filter) {
        checkUsable();
        String id = requireScopeId(scopeId);
        Entry entry = catalogs.get(id);
        if (entry == null || entry.database().isDisposed()) {
            throw new IllegalStateException("watch requires an open catalog for scope: " + id);
        }
        Path normalizedRoot = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        Registration registration = new Registration(id, rootId, normalizedRoot, filter, entry.catalog(), entry.order());
        Registration previous = registrations.put(id, registration);
        if (previous != null) {
            byRoot.remove(previous.root());
        }
        byRoot.put(normalizedRoot, registration);
        ensureWatcher().setRoots(Set.copyOf(byRoot.keySet()));
    }

    /** Stops watching a scope; read, clear and dispose {@code globalWatcher} under the lock {@link #watch} holds. */
    @Override
    public synchronized void unwatch(String scopeId) {
        checkUsable();
        Registration registration = registrations.remove(requireScopeId(scopeId));
        if (registration == null) {
            return;
        }
        byRoot.remove(registration.root());
        VfsWatcher watcher = globalWatcher;
        if (watcher == null) {
            return;
        }
        if (byRoot.isEmpty()) {
            globalWatcher = null;
            Disposer.dispose(watcher); // Nothing left to watch: release the thread.
        } else {
            watcher.setRoots(Set.copyOf(byRoot.keySet()));
        }
    }

    /**
     * Reconciles the whole tree and publishes, as on an event overflow. Reads {@code registrations} without the lock:
     * racing with watch or unwatch can only skip or repeat one sync.
     */
    @Override
    public void syncFromDisk(String scopeId) {
        checkUsable();
        Registration registration = registrations.get(requireScopeId(scopeId));
        if (registration == null) {
            return; // Not watched: nobody to notify.
        }
        reconcile(registration.scopeId(), registration.rootId(), registration.filter());
    }

    /**
     * Publishes rows the writer already updated in the snapshot, without reconciling: a rescan would find no change.
     * Don't take {@code order}: the writer would wait behind a full reconcile, and subscribers handle current state.
     */
    @Override
    public void notifyChanged(String scopeId, List<VfsFileId> created, List<VfsFileId> updated) {
        checkUsable();
        Registration registration = registrations.get(requireScopeId(scopeId));
        if (registration == null) {
            return; // Not watched: nobody to notify.
        }
        List<VfsManagedFile> addedRows = resolveRows(registration.catalog(), created);
        List<VfsManagedFile> updatedRows = resolveRows(registration.catalog(), updated);
        if (addedRows.isEmpty() && updatedRows.isEmpty()) {
            return; // Nothing resolved: don't publish an empty event.
        }
        VfsScanResult result = new VfsScanResult(registration.rootId(), addedRows, updatedRows, List.of(), 0);
        historyFor(registration.scopeId()).ifPresent(store -> HistoryFeed.feed(store, result, HistorySource.APP, false));
        publish.accept(registration.scopeId(), result);
    }

    /** Resolves ids to current snapshot rows, skipping ids that are gone or belong to another scope. */
    private static List<VfsManagedFile> resolveRows(VfsCatalog catalog, List<VfsFileId> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<VfsManagedFile> rows = new ArrayList<>(ids.size());
        for (VfsFileId id : ids) {
            if (id != null) {
                catalog.findFile(id).ifPresent(rows::add);
            }
        }
        return rows;
    }

    /** Creates the shared watcher on first use. */
    private synchronized VfsWatcher ensureWatcher() {
        VfsWatcher watcher = globalWatcher;
        if (watcher == null) {
            watcher = watcherFactory.create(this::acceptPath, new VfsWatchListener() {
                @Override
                public void changed(Set<Path> affectedDirs) {
                    onChanged(affectedDirs);
                }

                @Override
                public void overflow(Set<Path> roots) {
                    onOverflow(roots);
                }
            });
            Disposer.register(this, watcher);
            globalWatcher = watcher;
        }
        return watcher;
    }

    /**
     * Watcher filter: applies the {@link ScanFilter} of the root's registration to the path and to its ancestors,
     * which the filter itself doesn't check. Rejected paths never reach the debounce queue.
     * Keep the ancestor check: writes to a database inside a watched root would otherwise keep postponing the flush.
     */
    private boolean acceptPath(Path path, boolean directory) {
        Registration registration = findRegistration(path);
        return registration != null
                && registration.filter().include(path, directory)
                && !withinExcludedSubtree(registration, path);
    }

    /**
     * Returns whether an ancestor of the path below the root is excluded, testing each as a directory; the path itself
     * is left to the caller. Path arithmetic only, no I/O.
     * Never test the root: a root that happens to be named like an excluded folder must still be watched.
     */
    private static boolean withinExcludedSubtree(Registration registration, Path path) {
        Path root = registration.root();
        for (Path current = path.getParent();
             current != null && !current.equals(root) && current.startsWith(root);
             current = current.getParent()) {
            if (!registration.filter().include(current, true)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds the registration whose root is the path itself or its nearest registered ancestor.
     * Don't switch to a sorted map's floorEntry plus startsWith: a sibling root sorting in between drops the event.
     */
    private Registration findRegistration(Path path) {
        for (Path current = path; current != null; current = current.getParent()) {
            Registration registration = byRoot.get(current);
            if (registration != null) {
                return registration;
            }
        }
        return null;
    }

    /**
     * Groups debounced directories by scope, reconciles each non-recursively and publishes one merged diff per scope.
     * Keep it non-recursive: a change at the root would otherwise rescan the whole tree.
     */
    private void onChanged(Set<Path> affectedDirs) {
        Map<String, List<Path>> byScope = new LinkedHashMap<>(); // Reconcile in event order.
        for (Path dir : affectedDirs) {
            Registration registration = findRegistration(dir);
            if (registration != null) {
                byScope.computeIfAbsent(registration.scopeId(), key -> new ArrayList<>()).add(dir);
            }
        }
        for (Map.Entry<String, List<Path>> entry : byScope.entrySet()) {
            String scopeId = entry.getKey();
            Registration registration = registrations.get(scopeId);
            if (registration == null) {
                continue; // Unwatched meanwhile.
            }
            try {
                refreshAndPublish(registration, entry.getValue());
            } catch (RuntimeException failure) {
                if (Cancellation.isCancellation(failure)) {
                    LOG.debug("vfs.change.cancelled scope={}", scopeId);
                    if (Thread.currentThread().isInterrupted()) {
                        return; // Stop the batch only if the debounce thread itself is cancelled.
                    }
                    continue;
                }
                LOG.warn("vfs.change.failed scope={}", scopeId, failure);
            }
        }
    }

    /**
     * Reconciles each affected directory level by level and publishes the merged result. A failure loses only its own
     * directory, since nobody reads this thread's futures; a cancellation propagates and skips the remaining ones.
     */
    private void refreshAndPublish(Registration registration, List<Path> dirs) {
        synchronized (registration.order()) {
            List<VfsScanResult> results = new ArrayList<>();
            try {
                for (Path dir : dirs) {
                    try {
                        for (Path level : refreshChain(registration, dir)) {
                            refreshLevel(registration, level).ifPresent(results::add);
                        }
                    } catch (RuntimeException failure) {
                        if (Cancellation.isCancellation(failure)) {
                            throw failure;
                        }
                        LOG.warn("vfs.change.dirFailed scope={} dir={}", registration.scopeId(), dir, failure);
                    }
                }
            } finally {
                publishMerged(registration, results); // Publish whatever was reconciled.
            }
        }
    }

    private Optional<VfsScanResult> refreshLevel(Registration registration, Path level) {
        String relative = registration.root().relativize(level).toString().replace('\\', '/');
        try {
            return Optional.of(registration.catalog()
                    .refresh(registration.rootId(), relative, false, registration.filter()));
        } catch (RuntimeException failure) {
            if (Cancellation.isCancellation(failure)) {
                throw failure; // Skips this scope's other directories; onChanged decides the rest.
            }
            LOG.warn("vfs.refresh.failed scope={} path={}", registration.scopeId(), relative, failure);
            return Optional.empty();
        }
    }

    /**
     * Returns the levels to reconcile, top-down from {@link #refreshTarget}'s result to {@code dir}; empty drops the
     * change. Each level registers the next, so a copied-in folder tree comes out complete in any event order.
     * Don't reconcile the target alone or recursively: the first misses deep files, the second rescans the root.
     */
    private static List<Path> refreshChain(Registration registration, Path dir) {
        Path target = refreshTarget(registration, dir);
        if (target == null) {
            return List.of();
        }
        Deque<Path> chain = new ArrayDeque<>();
        for (Path current = dir; current != null && !current.equals(target); current = current.getParent()) {
            chain.addFirst(current);
        }
        chain.addFirst(target);
        return new ArrayList<>(chain);
    }

    /**
     * Resolves where to reconcile a reported directory, which may be excluded or not registered yet: the nearest of
     * the directory and its ancestors with a PRESENT row, or the root; like IntelliJ's {@code markFlatDirsDirty}.
     *
     * @return the target, or {@code null} if {@code dir} or an ancestor is excluded
     */
    private static Path refreshTarget(Registration registration, Path dir) {
        Path root = registration.root();
        // Path-only checks first; they repeat acceptPath on purpose (see DefaultManagedVfsServiceWatchTest).
        if (withinExcludedSubtree(registration, dir)) {
            return null;
        }
        if (!dir.equals(root) && !registration.filter().include(dir, true)) {
            return null;
        }
        // Walk up to the nearest PRESENT row; the root is always a valid target, though it has no row.
        for (Path current = dir; current != null && current.startsWith(root); current = current.getParent()) {
            if (current.equals(root)) {
                return root;
            }
            // A directory gone from disk or turned into a link can't be the target; its parent's pass marks it.
            if (Files.notExists(current) || FileLinks.isLink(current)) {
                continue;
            }
            String relative = root.relativize(current).toString().replace('\\', '/');
            // Require PRESENT: a pass never reconciles its own base row, so a MISSING base would stay hidden.
            if (registration.catalog().findFile(registration.rootId(), relative)
                    .filter(row -> row.status() == VfsFileStatus.PRESENT).isPresent()) {
                return current;
            }
        }
        return null;
    }

    /** On event overflow, rescans each root recursively; one root's failure doesn't affect the others. */
    private void onOverflow(Set<Path> roots) {
        for (Path root : roots) {
            Registration registration = byRoot.get(root);
            if (registration == null) {
                continue;
            }
            try {
                synchronized (registration.order()) {
                    VfsScanResult result = registration.catalog()
                            .refresh(registration.rootId(), "", true, registration.filter());
                    publishMerged(registration, List.of(result));
                }
            } catch (RuntimeException failure) {
                if (Cancellation.isCancellation(failure)) {
                    LOG.debug("vfs.overflow.cancelled scope={} root={}", registration.scopeId(), root);
                    if (Thread.currentThread().isInterrupted()) {
                        return; // Stop only if the debounce thread itself is cancelled.
                    }
                    continue;
                }
                LOG.warn("vfs.overflow.failed scope={} root={}", registration.scopeId(), root, failure);
            }
        }
    }

    /**
     * Merges a scope's diffs into one batch event and publishes it.
     * Feed history first: a subscriber may query history on the event, so these writes must already be queued.
     */
    private void publishMerged(Registration registration, List<VfsScanResult> results) {
        List<VfsManagedFile> added = new ArrayList<>();
        List<VfsManagedFile> updated = new ArrayList<>();
        List<VfsManagedFile> missing = new ArrayList<>();
        int unchanged = 0;
        for (VfsScanResult result : results) {
            added.addAll(result.added());
            updated.addAll(result.updated());
            missing.addAll(result.missing());
            unchanged += result.unchanged();
        }
        VfsScanResult merged = new VfsScanResult(registration.rootId(), added, updated, missing, unchanged);
        try {
            historyFor(registration.scopeId())
                    .ifPresent(store -> HistoryFeed.feed(store, merged, HistorySource.EXTERNAL, false));
        } catch (RuntimeException failure) {
            LOG.warn("vfs.history.feedFailed scope={}", registration.scopeId(), failure); // Still publish the event.
        }
        publish.accept(registration.scopeId(), merged);
    }

    @Override
    public VfsManagedFile fileWritten(String scopeId, VfsFileId fileId) {
        checkUsable();
        VfsManagedFile row = requireCatalog(scopeId).refreshFile(fileId);
        historyFor(scopeId).ifPresent(store -> HistoryFeed.capture(store, row, HistorySource.APP));
        return row;
    }

    @Override
    public void fileOpened(String scopeId, VfsFileId fileId) {
        checkUsable();
        Optional<LocalHistoryStore> store = historyFor(scopeId);
        if (store.isEmpty()) {
            return;
        }
        requireCatalog(scopeId).findFile(fileId).ifPresent(row -> HistoryFeed.baselineOnOpen(store.get(), row));
    }

    @Override
    public VfsScanResult reconcile(String scopeId, VfsRootId rootId, ScanFilter filter) {
        checkUsable();
        Entry entry = requireEntry(scopeId);
        VfsCatalog catalog = entry.catalog();
        synchronized (entry.order()) {
            VfsScanResult result = catalog.refresh(rootId, "", true, filter);
            historyFor(scopeId).ifPresent(store -> {
                HistoryFeed.feed(store, result, HistorySource.EXTERNAL, true);
                HistoryFeed.baselineTree(store, catalog, rootId);
            });
            if (registrations.containsKey(scopeId)) {
                publish.accept(scopeId, result);
            }
            return result;
        }
    }

    private VfsCatalog requireCatalog(String scopeId) {
        return requireEntry(scopeId).catalog();
    }

    private Entry requireEntry(String scopeId) {
        Entry entry = catalogs.get(requireScopeId(scopeId));
        if (entry == null) {
            throw new IllegalStateException("No catalog is open for scope: " + scopeId);
        }
        return entry;
    }

    private Optional<LocalHistoryStore> historyFor(String scopeId) {
        try {
            return historyLookup.apply(scopeId);
        } catch (RuntimeException failure) {
            LOG.warn("vfs.history.lookupFailed scope={}", scopeId, failure);
            return Optional.empty();
        }
    }

    private static Optional<LocalHistoryStore> lookupHistory(String scopeId) {
        Application app = ApplicationManager.getApplicationIfCreated();
        if (app == null || app.isDisposed()) {
            return Optional.empty();
        }
        LocalHistoryService service = app.getService(LocalHistoryService.class);
        return service == null ? Optional.empty() : service.findOpenStore(scopeId);
    }

    /**
     * Disposes the watcher and closes all open catalogs.
     * Must share the {@link #watch} lock, or a racing watch could set roots on a watcher being disposed.
     */
    @Override
    public synchronized void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        VfsWatcher watcher = globalWatcher;
        globalWatcher = null;
        if (watcher != null) {
            Disposer.dispose(watcher);
        }
        registrations.clear();
        byRoot.clear();
        for (Entry entry : catalogs.values()) {
            entry.catalog().close();
        }
        catalogs.clear();
    }

    private void checkUsable() {
        if (disposed) {
            throw new IllegalStateException("Managed VFS service is disposed");
        }
    }

    private static DatabaseVfsCatalogBackend requireDatabaseBackend(VfsCatalogBackend backend) {
        Objects.requireNonNull(backend, "catalogBackend");
        if (backend instanceof DatabaseVfsCatalogBackend databaseBackend) {
            return databaseBackend;
        }
        throw new IllegalArgumentException("Unsupported VFS catalog backend: " + backend.backendId());
    }

    private static String requireScopeId(String scopeId) {
        String cleaned = Objects.requireNonNull(scopeId, "scopeId").trim();
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("scopeId must not be blank");
        }
        return cleaned;
    }
}
