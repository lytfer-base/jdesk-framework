package com.jdesk.frame.storage.sqlite;

import com.jdesk.core.app.PathManager;
import com.jdesk.core.disposer.Disposable;
import com.jdesk.core.disposer.Disposer;
import com.jdesk.frame.storage.DatabaseDescriptor;
import com.jdesk.frame.storage.DatabaseHandle;
import com.jdesk.frame.storage.DatabaseService;
import com.jdesk.frame.storage.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * SQLite {@link DatabaseService}: keeps one open handle per database id, places a database without an explicit
 * location in a per-owner folder under the system directory, and disposes the remaining handles when disposed.
 */
public final class SqliteDatabaseService implements DatabaseService, Disposable {

    private static final Logger LOG = LoggerFactory.getLogger(SqliteDatabaseService.class);

    private final ConcurrentMap<String, SqliteDatabaseHandle> openDatabases = new ConcurrentHashMap<>();
    private volatile boolean disposed;

    /**
     * Opens the database, running migrations and preparing entity tables, or returns the handle already open for the
     * id; a reopen ignores the new descriptor's migrations, and a reopen at a different file throws.
     * Give each database a single descriptor entry point, so its location is decided in one place.
     */
    @Override
    public DatabaseHandle open(DatabaseDescriptor descriptor) {
        if (disposed) {
            throw new StorageException("Database service is disposed");
        }
        discardIfFileIsGone(descriptor.id());
        return openDatabases.compute(descriptor.id(), (id, existing) -> {
            if (existing != null && !existing.isDisposed()) {
                requireSamePath(existing, descriptor);
                return existing;
            }
            SqliteDatabaseHandle handle = new SqliteDatabaseHandle(descriptor, resolvePath(descriptor),
                    // Remove by identity, so a disposing handle can't remove the one reopened under its id.
                    closed -> openDatabases.remove(id, closed));
            handle.initialize();
            return handle;
        });
    }

    /**
     * Drops a cached handle whose file is gone, which would otherwise silently recreate it as an empty database
     * without the owner's tables. Must run outside {@code compute}: disposing calls back to remove the same key.
     */
    private void discardIfFileIsGone(String databaseId) {
        SqliteDatabaseHandle cached = openDatabases.get(databaseId);
        if (cached == null || cached.isDisposed() || Files.exists(cached.path())) {
            return;
        }
        LOG.warn("storage.open.fileGone database={} path={} (reopening the handle)", databaseId, cached.path());
        close(databaseId);
    }

    /**
     * Fails when an open id is reopened at a different file.
     * Don't switch to {@link #resolvePath}: it creates directories even for a location being rejected.
     */
    private static void requireSamePath(SqliteDatabaseHandle existing, DatabaseDescriptor descriptor) {
        Path wanted = targetPath(descriptor);
        if (!existing.path().equals(wanted)) {
            throw new StorageException("Database " + descriptor.id() + " is already open at "
                    + existing.path() + " but was reopened for " + wanted
                    + ". One id must map to exactly one file: a reopen returns the existing handle,"
                    + " so this descriptor's location and migrations would be silently ignored."
                    + " Give this database a single descriptor entry point.");
        }
    }

    /**
     * Looks up an already opened and still usable database handle.
     */
    @Override
    public Optional<DatabaseHandle> findOpen(String databaseId) {
        SqliteDatabaseHandle handle = openDatabases.get(databaseId);
        return handle == null || handle.isDisposed() ? Optional.empty() : Optional.of(handle);
    }

    /**
     * Closes a database by id and removes it from the service registry.
     */
    @Override
    public boolean close(String databaseId) {
        SqliteDatabaseHandle handle = openDatabases.remove(databaseId);
        if (handle == null) {
            return false;
        }
        Disposer.dispose(handle);
        return true;
    }

    /**
     * Releases all open database handles as an application-shutdown safety net.
     */
    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        for (SqliteDatabaseHandle handle : openDatabases.values()) {
            Disposer.dispose(handle);
        }
        openDatabases.clear();
    }

    /** Where this descriptor's file goes, without touching the filesystem (see {@link #requireSamePath}). */
    private static Path targetPath(DatabaseDescriptor descriptor) {
        return descriptor.location() != null
                ? descriptor.location().resolve(descriptor.name() + ".sqlite").toAbsolutePath().normalize()
                : resolveDefaultPath(descriptor);
    }

    private static Path resolvePath(DatabaseDescriptor descriptor) {
        Path path = targetPath(descriptor);
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException e) {
            throw new StorageException("Failed to create database directory: " + path.getParent(), e);
        }
        return path;
    }

    private static Path resolveDefaultPath(DatabaseDescriptor descriptor) {
        Path root = PathManager.getSystemDir()
                .resolve("databases")
                .resolve(descriptor.owner().pathSegment())
                .toAbsolutePath()
                .normalize();
        Path path = root.resolve(descriptor.name() + ".sqlite").toAbsolutePath().normalize();
        if (!path.startsWith(root)) {
            throw new StorageException("Database path escaped owner root: " + path);
        }
        return path;
    }
}
