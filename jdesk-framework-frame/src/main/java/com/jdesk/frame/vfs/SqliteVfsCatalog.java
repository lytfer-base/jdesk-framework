package com.jdesk.frame.vfs;

import com.jdesk.core.io.Cancellation;
import com.jdesk.core.io.FileLinks;
import com.jdesk.core.vfs.NativeFileIdReader;
import com.jdesk.core.vfs.ScanFilter;
import com.jdesk.core.vfs.VfsCatalog;
import com.jdesk.core.vfs.VfsFileId;
import com.jdesk.core.vfs.VfsFileStatus;
import com.jdesk.core.vfs.VfsManagedFile;
import com.jdesk.core.vfs.VfsRoot;
import com.jdesk.core.vfs.VfsRootId;
import com.jdesk.core.vfs.VfsScanResult;
import com.jdesk.frame.storage.DatabaseHandle;
import com.jdesk.frame.storage.DatabaseRow;
import com.jdesk.frame.storage.DatabaseSession;
import com.jdesk.frame.storage.StorageException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Collator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * SQLite implementation of one scope's managed VFS catalog. Its tables live in the supplied database, which may also
 * hold the caller's own tables; the caller owns that database and closes it.
 */
public final class SqliteVfsCatalog implements VfsCatalog {

    private static final Logger LOG = LoggerFactory.getLogger(SqliteVfsCatalog.class);

    /**
     * Maximum parameters bound in one {@code in (...)} list, since SQLite caps parameters per statement
     * ({@code SQLITE_MAX_VARIABLE_NUMBER}, 999 in older versions). All batches run in one transaction.
     */
    static final int DELETE_BATCH_SIZE = 500;

    private final String scopeId;
    private final DatabaseHandle database;
    /**
     * Mutable and shared by the whole catalog; safe because it runs inside {@code database.transaction(...)}, on the
     * database's single writer thread.
     * Use it only inside a transaction: {@link Collator} isn't documented as thread-safe.
     */
    private final Collator collator;
    /** Reads native file ids, the second matching clue after {@code relative_path}; empty on unsupported platforms. */
    private final NativeFileIdReader nativeFileIds = NativeFileIdReaders.forCurrentPlatform();
    private volatile boolean closed;

    /**
     * Opens the catalog with {@link Locale#getDefault()}; see {@link VfsFileNameSort} for the sort keys.
     * Prefer the three-argument constructor with a fixed locale: the default one follows the UI language.
     */
    public SqliteVfsCatalog(String scopeId, DatabaseHandle database) {
        this(scopeId, database, Locale.getDefault());
    }

    /**
     * Opens a catalog for one scope and ensures the VFS tables exist.
     *
     * @param locale collation for {@code file_name_sort}; must stay the same for a database, since stored keys aren't
     *               recomputed and two locales would leave two generations of keys in one column
     */
    public SqliteVfsCatalog(String scopeId, DatabaseHandle database, Locale locale) {
        this.scopeId = requireText("scopeId", scopeId);
        this.database = Objects.requireNonNull(database, "database");
        this.collator = VfsFileNameSort.collatorFor(locale);
        ensureSchema();
    }

    /**
     * Returns the application or library scope this catalog belongs to.
     */
    @Override
    public String scopeId() {
        return scopeId;
    }

    /**
     * Registers a root by its stable key: a known key gets its {@code root_path} updated (the root moved), a new key
     * gets a new id. Throws {@link StorageException} if the path already belongs to another key.
     */
    @Override
    public VfsRootId registerRoot(String rootKey, String name, Path rootPath) {
        checkOpen();
        String key = requireText("rootKey", rootKey);
        String rootName = requireText("name", name);
        Path normalizedPath = Objects.requireNonNull(rootPath, "rootPath").toAbsolutePath().normalize();
        return database.transaction(session -> findRootByKey(session, key)
                .map(root -> {
                    session.update("""
                            update jdesk_vfs_roots set root_path = ?, updated_at = ?
                            where root_id = ?
                            """, normalizedPath, now(), root.rootId().value());
                    return root.rootId();
                })
                .orElseGet(() -> {
                    requirePathUnclaimed(session, key, normalizedPath);
                    return insertRoot(session, key, rootName, normalizedPath);
                }));
    }

    /** Fails with the owning key when the path already belongs to another root, instead of a bare constraint error. */
    private static void requirePathUnclaimed(DatabaseSession session, String rootKey, Path rootPath) {
        session.queryOne("select root_key from jdesk_vfs_roots where root_path = ?",
                        row -> row.getString("root_key"), rootPath)
                .ifPresent(owner -> {
                    throw new StorageException("Root path is already registered: " + rootPath
                            + " (owned by rootKey=\"" + owner + "\", requested rootKey=\"" + rootKey + "\"). "
                            + "A path can belong to only one root; registering it under another key is not supported. "
                            + "If the database comes from an older version whose roots have no key, "
                            + "delete the database file and reopen.");
                });
    }

    /**
     * Lists all roots known to this catalog.
     */
    @Override
    public List<VfsRoot> listRoots() {
        checkOpen();
        return database.read(session -> session.queryList("""
                select root_id, name, root_path
                from jdesk_vfs_roots
                order by created_at, root_path
                """, SqliteVfsCatalog::mapRoot));
    }

    /**
     * Registers a file under a root, assigning a new id only when no row has the same root and path. Both branches
     * reconcile the row with the disk, and an existing row keeps its file id, as in a scan.
     * Don't skip the disk check on a hit: a stale MISSING row would hide a file just recreated at that path.
     */
    @Override
    public VfsManagedFile registerFile(VfsRootId rootId, String relativePath, boolean directory) {
        checkOpen();
        Objects.requireNonNull(rootId, "rootId");
        String normalizedRelativePath = VfsManagedFile.normalizeRelativePath(relativePath);
        return database.transaction(session -> findFile(session, rootId, normalizedRelativePath)
                .map(current -> reconcileRegistered(session, current, directory))
                .orElseGet(() -> insertFile(session, rootId, normalizedRelativePath, directory)));
    }

    /**
     * Reconciles a registered row with the disk: a path not on disk is MISSING, already MISSING returns unchanged,
     * and a steady row returns without a write ({@code registerFileDoesNotWriteWhenNothingChanged}).
     * Keep the native id read after the early returns: it costs a system call, and no test catches moving it.
     */
    private VfsManagedFile reconcileRegistered(DatabaseSession session, VfsManagedFile current,
                                               boolean directory) {
        Path path = current.absolutePath();
        Path rootPath = findRoot(session, current.rootId())
                .orElseThrow(() -> new StorageException("VFS root not found: " + current.rootId().value()))
                .rootPath();
        BasicFileState state = readFileState(rootPath, path, directory);
        if (state.status() != VfsFileStatus.PRESENT) {
            return current.status() == VfsFileStatus.MISSING
                    ? current
                    : markStatus(session, current.fileId(), VfsFileStatus.MISSING);
        }
        boolean contentChanged = !directory
                && (current.size() != state.size() || current.modifiedAt() != state.modifiedAt());
        if (!contentChanged && current.status() == VfsFileStatus.PRESENT) {
            return current;
        }
        ScannedEntry entry = new ScannedEntry(current.relativePath(), directory, state.size(),
                state.modifiedAt(), state.createdAt(), nativeIdOf(path));
        refreshNativeId(session, current.fileId(), entry.nativeFileId());
        return updateScanned(session, current.fileId(), entry, contentChanged);
    }

    /**
     * Resolves a managed file by its stable ID.
     */
    @Override
    public Optional<VfsManagedFile> findFile(VfsFileId fileId) {
        checkOpen();
        Objects.requireNonNull(fileId, "fileId");
        return database.read(session -> session.queryOne(
                FILE_SELECT + "where f.file_id = ?", SqliteVfsCatalog::mapFile, fileId.value()));
    }

    /**
     * Resolves a managed file by root and normalized relative path.
     */
    @Override
    public Optional<VfsManagedFile> findFile(VfsRootId rootId, String relativePath) {
        checkOpen();
        Objects.requireNonNull(rootId, "rootId");
        return database.read(session -> findFile(session, rootId, VfsManagedFile.normalizeRelativePath(relativePath)));
    }

    /**
     * Moves a row to a new path, keeping its file id and read-only flag; another MISSING row at the target is deleted
     * in the same transaction. Throws {@link StorageException}, changing nothing, if a PRESENT row holds the target.
     */
    @Override
    public VfsManagedFile moveFile(VfsFileId fileId, VfsRootId newRootId, String newRelativePath) {
        checkOpen();
        Objects.requireNonNull(fileId, "fileId");
        Objects.requireNonNull(newRootId, "newRootId");
        String normalizedRelativePath = VfsManagedFile.normalizeRelativePath(newRelativePath);
        return database.transaction(session -> {
            VfsManagedFile existing = findFile(session, fileId)
                    .orElseThrow(() -> new StorageException("VFS file not found: " + fileId.value()));
            VfsRoot root = findRoot(session, newRootId)
                    .orElseThrow(() -> new StorageException("VFS root not found: " + newRootId.value()));
            clearPathFor(session, fileId, newRootId, normalizedRelativePath);
            Path absolutePath = root.rootPath().resolve(normalizedRelativePath).toAbsolutePath().normalize();
            long now = now();
            BasicFileState state = readFileState(root.rootPath(), absolutePath, existing.directory());
            String fileName = fileNameOf(normalizedRelativePath);
            // Leave file_created_at alone: the file only changed place.
            session.update("""
                    update jdesk_vfs_files
                    set root_id = ?, relative_path = ?, parent_path = ?, file_name = ?, file_name_sort = ?,
                        status = ?, size = ?, modified_at = ?,
                        content_version = content_version + 1, updated_at = ?
                    where file_id = ?
                    """,
                    newRootId.value(),
                    normalizedRelativePath,
                    parentPathOf(normalizedRelativePath),
                    fileName,
                    VfsFileNameSort.key(collator, fileName),
                    state.status(),
                    state.size(),
                    state.modifiedAt(),
                    now,
                    fileId.value());
            return findFile(session, fileId)
                    .orElseThrow(() -> new StorageException("VFS file disappeared after move: " + fileId.value()));
        });
    }

    /** Clears the target path for {@code mover}: deletes another MISSING row there and throws for a PRESENT one. */
    private static void clearPathFor(DatabaseSession session, VfsFileId mover, VfsRootId rootId, String relativePath) {
        Optional<VfsManagedFile> occupant = findFile(session, rootId, relativePath)
                .filter(row -> !row.fileId().equals(mover));
        if (occupant.isEmpty()) {
            return;
        }
        if (occupant.get().status() == VfsFileStatus.PRESENT) {
            throw new StorageException("VFS path is held by another present file: " + relativePath);
        }
        session.update("delete from jdesk_vfs_files where file_id = ?", occupant.get().fileId().value());
    }

    /**
     * Updates the catalog lifecycle status of a managed file.
     */
    @Override
    public VfsManagedFile updateStatus(VfsFileId fileId, VfsFileStatus status) {
        checkOpen();
        Objects.requireNonNull(fileId, "fileId");
        Objects.requireNonNull(status, "status");
        return database.transaction(session -> {
            if (findFile(session, fileId).isEmpty()) {
                throw new StorageException("VFS file not found: " + fileId.value());
            }
            session.update("""
                    update jdesk_vfs_files
                    set status = ?, content_version = content_version + 1, updated_at = ?
                    where file_id = ?
                    """, status.name(), now(), fileId.value());
            return findFile(session, fileId)
                    .orElseThrow(() -> new StorageException("VFS file disappeared after status update: " + fileId.value()));
        });
    }

    /** Sets the flag; don't bump {@code content_version} here, or caches keyed on it are invalidated for nothing. */
    @Override
    public boolean setReadOnly(VfsFileId fileId, boolean readOnly) {
        checkOpen();
        Objects.requireNonNull(fileId, "fileId");
        return database.transaction(session -> session.update(
                "update jdesk_vfs_files set read_only = ?, updated_at = ? where file_id = ?",
                readOnly, now(), fileId.value()) > 0);
    }

    /** Deletes one managed file row. */
    @Override
    public void removeFile(VfsFileId fileId) {
        checkOpen();
        Objects.requireNonNull(fileId, "fileId");
        database.transaction(session -> {
            session.update("delete from jdesk_vfs_files where file_id = ?", fileId.value());
            return null;
        });
    }

    /** Deletes many managed file rows in one transaction. */
    @Override
    public void removeFiles(Collection<VfsFileId> fileIds) {
        removeFiles(fileIds, DELETE_BATCH_SIZE);
    }

    /**
     * Like {@link #removeFiles(Collection)} with a given batch size, so tests can exercise the batching.
     * Other code must call {@link #removeFiles(Collection)}.
     */
    void removeFiles(Collection<VfsFileId> fileIds, int batchSize) {
        checkOpen();
        Objects.requireNonNull(fileIds, "fileIds");
        List<String> values = fileIds.stream().map(VfsFileId::value).toList();
        if (values.isEmpty()) {
            // Don't open a transaction for nothing: each one opens a connection and hops to the writer thread.
            return;
        }
        database.transaction(session -> {
            for (int from = 0; from < values.size(); from += batchSize) {
                List<String> batch = values.subList(from, Math.min(from + batchSize, values.size()));
                session.update("delete from jdesk_vfs_files where file_id in (" + placeholders(batch.size()) + ")",
                        batch.toArray());
            }
            return null;
        });
    }

    /** Returns {@code count} comma-separated placeholders; ids are always bound, never spliced into the SQL. */
    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    /** Lists a directory's direct children from the snapshot without reading the disk. */
    @Override
    public List<VfsManagedFile> listChildren(VfsRootId rootId, String dirRelativePath) {
        checkOpen();
        Objects.requireNonNull(rootId, "rootId");
        String parent = (dirRelativePath == null || dirRelativePath.isBlank())
                ? "" : VfsManagedFile.normalizeRelativePath(dirRelativePath);
        return database.read(session -> session.queryList(
                FILE_SELECT + """
                        where f.root_id = ? and f.parent_path = ?
                        order by f.directory desc, f.relative_path collate nocase
                        """, SqliteVfsCatalog::mapFile, rootId.value(), parent));
    }

    /** Lists the whole tree in one query; the default implementation asks per directory, opening a connection each. */
    @Override
    public List<VfsManagedFile> listAll(VfsRootId rootId) {
        checkOpen();
        Objects.requireNonNull(rootId, "rootId");
        return database.read(session -> session.queryList(FILE_SELECT + "where f.root_id = ?",
                SqliteVfsCatalog::mapFile, rootId.value()));
    }

    /** Resolves an absolute disk path to a managed file by matching root path prefixes. */
    @Override
    public Optional<VfsManagedFile> findFile(Path absolutePath) {
        checkOpen();
        Objects.requireNonNull(absolutePath, "absolutePath");
        Path target = absolutePath.toAbsolutePath().normalize();
        return database.read(session -> {
            for (VfsRoot root : session.queryList("""
                    select root_id, name, root_path from jdesk_vfs_roots
                    """, SqliteVfsCatalog::mapRoot)) {
                Path base = root.rootPath();
                if (target.startsWith(base)) {
                    String rel = base.relativize(target).toString().replace('\\', '/');
                    if (rel.isEmpty()) {
                        continue;
                    }
                    return findFile(session, root.rootId(), rel);
                }
            }
            return Optional.empty();
        });
    }

    /**
     * Reconciles a subtree with the disk, writing the differences to the snapshot and returning them without
     * publishing events. An interrupt of the writer thread throws {@link java.util.concurrent.CancellationException}
     * and rolls back the whole pass.
     */
    @Override
    public VfsScanResult refresh(VfsRootId rootId, String relativePath, boolean recursive, ScanFilter filter) {
        checkOpen();
        Objects.requireNonNull(rootId, "rootId");
        ScanFilter scanFilter = filter != null ? filter : ScanFilter.all();
        String baseRel = (relativePath == null || relativePath.isBlank())
                ? "" : VfsManagedFile.normalizeRelativePath(relativePath);
        return database.transaction(session -> {
            VfsRoot root = findRoot(session, rootId)
                    .orElseThrow(() -> new StorageException("VFS root not found: " + rootId.value()));
            Path baseDir = baseRel.isEmpty()
                    ? root.rootPath()
                    : root.rootPath().resolve(baseRel).toAbsolutePath().normalize();

            List<VfsManagedFile> added = new ArrayList<>();
            List<VfsManagedFile> updated = new ArrayList<>();
            List<VfsManagedFile> missing = new ArrayList<>();
            int[] unchanged = {0};
            Set<String> seen = new HashSet<>();

            // New paths not in the snapshot; don't insert yet: they may be renames, paired later in relocateMatched.
            List<ScannedEntry> newcomers = new ArrayList<>();

            // Load the scope once; reload it after explicit moves, since ordinary reconciling doesn't change paths.
            Map<VfsFileId, String> nativeSnapshot = new HashMap<>();
            List<VfsManagedFile> scope = loadScope(session, rootId, baseRel, recursive, nativeSnapshot);
            Map<String, VfsManagedFile> byPath = new HashMap<>(Math.max(16, scope.size() * 2));
            for (VfsManagedFile row : scope) {
                byPath.put(row.relativePath(), row);
            }

            boolean scanComplete = true;
            Set<String> incompleteDirs = Set.of();
            BaseDirState baseState = baseDirState(root.rootPath(), baseDir);
            if (baseState == BaseDirState.DIRECTORY) {
                ScanEntries scanned = walkEntries(root.rootPath(), baseDir, recursive, scanFilter);
                Set<String> relocated = relocateObservedMoves(session, rootId, scanned.entries(),
                        byPath, nativeSnapshot, scanFilter, added, missing);
                if (!relocated.isEmpty()) {
                    nativeSnapshot.clear();
                    scope = loadScope(session, rootId, baseRel, recursive, nativeSnapshot);
                    byPath.clear();
                    for (VfsManagedFile row : scope) byPath.put(row.relativePath(), row);
                }
                Set<String> observedNativeIds = new HashSet<>();
                for (ScannedEntry entry : scanned.entries()) {
                    if (!entry.nativeFileId().isEmpty()) observedNativeIds.add(entry.nativeFileId());
                }
                boolean completeRoot = baseRel.isEmpty() && recursive && scanned.complete() && scanned.unreadable().isEmpty();
                for (ScannedEntry entry : scanned.entries()) {
                    seen.add(entry.relativePath());
                    if (!relocated.contains(entry.relativePath())) {
                        reconcile(session, entry, byPath, nativeSnapshot, completeRoot, observedNativeIds,
                                root.rootPath(), newcomers, added, updated, unchanged);
                    }
                }
                // Unreadable isn't absent: count it as seen, or it would be marked MISSING below.
                seen.addAll(scanned.unreadable());
                scanComplete = scanned.complete();
                incompleteDirs = scanned.incompleteDirs();
            } else if (baseState == BaseDirState.UNREADABLE) {
                scanComplete = false; // Unreadable isn't absent: an unreachable network drive mustn't vanish.
            }
            // An absent base leaves seen empty with a complete scan, so every row under it becomes MISSING.

            List<VfsManagedFile> vanished = new ArrayList<>(detectMissing(scope, seen, scanComplete, incompleteDirs));

            // Pair vanished rows with newcomers by native id; matches move in place and leave both lists.
            relocateMatched(session, rootId, vanished, newcomers, added, missing);

            for (ScannedEntry entry : newcomers) {
                added.add(insertRow(session, rootId, entry.relativePath(), entry.directory(),
                        VfsFileStatus.PRESENT, entry.size(), entry.modifiedAt(), entry.createdAt(),
                        entry.nativeFileId()));
            }
            for (VfsManagedFile candidate : vanished) {
                missing.add(markStatus(session, candidate.fileId(), VfsFileStatus.MISSING));
            }
            for (VfsManagedFile candidate : vanished) {
                if (candidate.directory()) {
                    missing.addAll(markSubtreeMissing(session, rootId, candidate.relativePath()));
                }
            }
            return new VfsScanResult(rootId, added, updated, missing, unchanged[0]);
        });
    }

    /** Marks the PRESENT rows under a vanished directory MISSING; a non-recursive pass can't see them otherwise. */
    private List<VfsManagedFile> markSubtreeMissing(DatabaseSession session, VfsRootId rootId, String dirPath) {
        List<VfsManagedFile> rows = session.queryList(
                FILE_SELECT + "where f.root_id = ? and f.status = ? and f.relative_path like ? escape '\\'",
                SqliteVfsCatalog::mapFile, rootId.value(), VfsFileStatus.PRESENT.name(), likeEscape(dirPath) + "/%");
        List<VfsManagedFile> marked = new ArrayList<>(rows.size());
        for (VfsManagedFile row : rows) {
            marked.add(markStatus(session, row.fileId(), VfsFileStatus.MISSING));
        }
        return marked;
    }

    /** Current state of the directory a reconcile starts from. */
    private enum BaseDirState { DIRECTORY, ABSENT, UNREADABLE }

    /**
     * ABSENT if missing, not a directory, or reached through a link; UNREADABLE on other I/O errors such as a
     * disconnected network drive or missing permissions.
     */
    private static BaseDirState baseDirState(Path rootPath, Path baseDir) {
        try {
            if (!Files.readAttributes(baseDir, BasicFileAttributes.class).isDirectory()) {
                return BaseDirState.ABSENT;
            }
        } catch (NoSuchFileException e) {
            return BaseDirState.ABSENT;
        } catch (IOException e) {
            return BaseDirState.UNREADABLE;
        }
        return FileLinks.reachedThroughLink(rootPath, baseDir) ? BaseDirState.ABSENT : BaseDirState.DIRECTORY;
    }

    @Override
    public VfsManagedFile refreshFile(VfsFileId fileId) {
        checkOpen();
        Objects.requireNonNull(fileId, "fileId");
        return database.transaction(session -> {
            VfsManagedFile current = findFile(session, fileId)
                    .orElseThrow(() -> new StorageException("VFS file not found: " + fileId.value()));
            Path path = current.absolutePath();
            Path rootPath = findRoot(session, current.rootId())
                    .orElseThrow(() -> new StorageException("VFS root not found: " + current.rootId().value()))
                    .rootPath();
            if (FileLinks.reachedThroughLink(rootPath, path)) {
                return markStatus(session, fileId, VfsFileStatus.MISSING); // Links aren't followed, as in the walk.
            }
            BasicFileAttributes attrs;
            try {
                attrs = Files.readAttributes(path, BasicFileAttributes.class);
            } catch (IOException e) {
                // Unreadable attributes mean gone, or briefly unreadable: MISSING, like a path a scan doesn't find.
                return markStatus(session, fileId, VfsFileStatus.MISSING);
            }
            ScannedEntry entry = new ScannedEntry(current.relativePath(), attrs.isDirectory(),
                    attrs.isDirectory() ? 0 : attrs.size(),
                    attrs.lastModifiedTime().toMillis(), attrs.creationTime().toMillis(),
                    nativeIdOf(path));
            // Must refresh the native id too: rewriting a whole file may change it, leaving a stale value otherwise.
            refreshNativeId(session, fileId, entry.nativeFileId());
            // Bump the version only when size or mtime changed, as reconcile does; caches keyed on it rely on that.
            boolean contentChanged = !entry.directory()
                    && (current.size() != entry.size() || current.modifiedAt() != entry.modifiedAt());
            return updateScanned(session, fileId, entry, contentChanged);
        });
    }

    /**
     * Applies moves backed by a native id before ordinary reconciling, so a replacement at the old path is handled
     * normally. Directories match old rows outside this pass's scope; renames inside it go to {@link #relocateMatched}.
     */
    private Set<String> relocateObservedMoves(DatabaseSession session, VfsRootId rootId,
                                              List<ScannedEntry> entries, Map<String, VfsManagedFile> byPath,
                                              Map<VfsFileId, String> nativeSnapshot, ScanFilter filter,
                                              List<VfsManagedFile> added, List<VfsManagedFile> missing) {
        Map<String, List<ScannedEntry>> arrivals = new HashMap<>();
        Set<String> scannedPaths = new HashSet<>();
        for (ScannedEntry entry : entries) {
            scannedPaths.add(entry.relativePath());
            if (entry.nativeFileId().isEmpty()) continue;
            VfsManagedFile samePath = byPath.get(entry.relativePath());
            if (samePath == null || !entry.nativeFileId().equals(nativeSnapshot.get(samePath.fileId()))) {
                arrivals.computeIfAbsent(entry.nativeFileId(), key -> new ArrayList<>()).add(entry);
            }
        }
        if (arrivals.isEmpty()) return Set.of();
        Map<String, List<VfsManagedFile>> candidates = new HashMap<>();
        List<String> ids = new ArrayList<>(arrivals.keySet());
        for (int offset = 0; offset < ids.size(); offset += DELETE_BATCH_SIZE) {
            List<String> batch = ids.subList(offset, Math.min(ids.size(), offset + DELETE_BATCH_SIZE));
            List<Object> params = new ArrayList<>();
            params.add(rootId.value());
            params.addAll(batch);
            session.queryList(FILE_SELECT + "where f.root_id=? and f.native_file_id in ("
                            + String.join(",", Collections.nCopies(batch.size(), "?")) + ")",
                    row -> {
                        VfsManagedFile file = mapFile(row);
                        candidates.computeIfAbsent(row.getString("native_file_id"), key -> new ArrayList<>()).add(file);
                        return file;
                    }, params.toArray());
        }
        Set<String> relocated = new HashSet<>();
        for (var group : arrivals.entrySet()) {
            List<VfsManagedFile> matches = candidates.get(group.getKey());
            if (group.getValue().size() != 1 || matches == null || matches.size() != 1) continue;
            ScannedEntry arrival = group.getValue().get(0);
            VfsManagedFile before = matches.get(0);
            if (before.directory() != arrival.directory() || before.relativePath().equals(arrival.relativePath())) continue;
            if (before.directory() && byPath.containsKey(before.relativePath())) continue;
            if (findFile(session, rootId, arrival.relativePath()).isPresent()) continue;
            String physicalAtOldPath = nativeIdOf(before.absolutePath());
            if (group.getKey().equals(physicalAtOldPath)
                    || (physicalAtOldPath.isEmpty() && Files.exists(before.absolutePath()))) continue;
            ScannedEntry replacement = null;
            if (!scannedPaths.contains(before.relativePath()) && !physicalAtOldPath.isEmpty()) {
                try {
                    BasicFileAttributes attrs = Files.readAttributes(before.absolutePath(), BasicFileAttributes.class);
                    if (filter.include(before.absolutePath(), attrs.isDirectory())) {
                        replacement = new ScannedEntry(before.relativePath(), attrs.isDirectory(),
                                attrs.isDirectory() ? 0 : attrs.size(), attrs.lastModifiedTime().toMillis(),
                                attrs.creationTime().toMillis(), physicalAtOldPath);
                    }
                } catch (IOException ignored) { /* The next reconcile of the source directory catches up. */ }
            }
            if (before.status() == VfsFileStatus.PRESENT) missing.add(before);
            added.add(relocate(session, before, arrival));
            relocated.add(arrival.relativePath());
            if (before.directory()) {
                // Mark the children MISSING as a source-first refresh would; deeper passes match them back by id.
                missing.addAll(markSubtreeMissing(session, rootId, before.relativePath()));
            }
            if (replacement != null) {
                added.add(insertRow(session, rootId, replacement.relativePath(), replacement.directory(),
                        VfsFileStatus.PRESENT, replacement.size(), replacement.modifiedAt(), replacement.createdAt(),
                        replacement.nativeFileId()));
            }
        }
        return relocated;
    }

    /** Updates a scanned entry's row, found by its pre-scan path, and refreshes its native id when safe. */
    private void reconcile(DatabaseSession session, ScannedEntry entry,
                           Map<String, VfsManagedFile> byPath, Map<VfsFileId, String> nativeSnapshot,
                           boolean completeRoot, Set<String> observedNativeIds, Path rootPath,
                           List<ScannedEntry> newcomers, List<VfsManagedFile> added,
                           List<VfsManagedFile> updated, int[] unchanged) {
        VfsManagedFile current = byPath.get(entry.relativePath());
        if (current == null) {
            // Don't assign an id yet: this may be a row about to go MISSING under a new name; pair them after the pass.
            newcomers.add(entry);
            return;
        }
        String previousNativeId = nativeSnapshot.getOrDefault(current.fileId(), "");
        // Refresh a changed id only once the original left the root, or a replacement would take its identity.
        if (previousNativeId.isEmpty() || previousNativeId.equals(entry.nativeFileId())
                || (completeRoot && !observedNativeIds.contains(previousNativeId))
                || (!completeRoot && originalLeftRoot(previousNativeId, current.absolutePath(), rootPath))) {
            refreshNativeId(session, current.fileId(), entry.nativeFileId());
        }
        boolean contentChanged = !entry.directory()
                && (current.size() != entry.size() || current.modifiedAt() != entry.modifiedAt());
        if (current.status() != VfsFileStatus.PRESENT) {
            // A row back from MISSING counts as added, as in relocateMatched; subscribers would miss it in updated.
            added.add(updateScanned(session, current.fileId(), entry, contentChanged));
        } else if (contentChanged) {
            updated.add(updateScanned(session, current.fileId(), entry, true));
        } else {
            unchanged[0]++;
        }
    }

    /**
     * Updates a scanned row in place, including {@code file_created_at} for a file deleted and recreated outside the
     * app. Leaves {@code file_name_sort} alone: the name is unchanged, and renames recompute it where they happen.
     */
    private VfsManagedFile updateScanned(DatabaseSession session, VfsFileId fileId,
                                         ScannedEntry entry, boolean bumpVersion) {
        String versionExpr = bumpVersion ? "content_version + 1" : "content_version";
        session.update("update jdesk_vfs_files set status = ?, size = ?, modified_at = ?, "
                        + "file_created_at = ?, content_version = " + versionExpr + ", updated_at = ? "
                        + "where file_id = ?",
                VfsFileStatus.PRESENT.name(), entry.size(), entry.modifiedAt(), entry.createdAt(),
                now(), fileId.value());
        return findFile(session, fileId)
                .orElseThrow(() -> new StorageException("VFS file disappeared after scan update: " + fileId.value()));
    }

    /**
     * Pairs vanished rows with newcomers by native id and moves each match in place, keeping its {@link VfsFileId};
     * both lists lose their matches. A pair still reports the old path missing and the new one added, with one id.
     * Pair only on a non-empty id unique on both sides: a wrong match silently attaches metadata to another file.
     */
    private void relocateMatched(DatabaseSession session, VfsRootId rootId,
                                 List<VfsManagedFile> vanished, List<ScannedEntry> newcomers,
                                 List<VfsManagedFile> added, List<VfsManagedFile> missing) {
        if (newcomers.isEmpty()) {
            return;
        }
        Map<String, List<ScannedEntry>> arrivals = new HashMap<>();
        for (ScannedEntry entry : newcomers) {
            if (!entry.nativeFileId().isEmpty()) {
                arrivals.computeIfAbsent(entry.nativeFileId(), k -> new ArrayList<>()).add(entry);
            }
        }
        if (arrivals.isEmpty()) {
            return;
        }
        // Include rows already MISSING: a per-directory pass may have marked a moved file's old row earlier.
        List<VfsManagedFile> resurrectable = loadMissingRows(session, rootId);
        if (vanished.isEmpty() && resurrectable.isEmpty()) {
            return;
        }
        Map<String, List<VfsManagedFile>> departures = new HashMap<>();
        for (VfsManagedFile gone : vanished) {
            indexByIdentity(session, departures, gone);
        }
        for (VfsManagedFile gone : resurrectable) {
            indexByIdentity(session, departures, gone);
        }
        for (Map.Entry<String, List<VfsManagedFile>> group : departures.entrySet()) {
            List<ScannedEntry> candidates = arrivals.get(group.getKey());
            if (candidates == null || candidates.size() != 1 || group.getValue().size() != 1) {
                continue;
            }
            VfsManagedFile before = group.getValue().get(0);
            ScannedEntry entry = candidates.get(0);
            // A directory never pairs with a file.
            if (before.directory() != entry.directory()) {
                continue;
            }
            // Report missing only for rows that vanished in this pass; earlier ones were already reported.
            if (vanished.remove(before)) {
                missing.add(before);
            }
            added.add(relocate(session, before, entry));
            newcomers.remove(entry);
        }
    }

    private static void indexByIdentity(DatabaseSession session,
                                        Map<String, List<VfsManagedFile>> index, VfsManagedFile row) {
        String identity = storedNativeId(session, row.fileId());
        if (!identity.isEmpty()) {
            index.computeIfAbsent(identity, k -> new ArrayList<>()).add(row);
        }
    }

    /** Rows already MISSING under the root: candidates for files that were merely moved. */
    private static List<VfsManagedFile> loadMissingRows(DatabaseSession session, VfsRootId rootId) {
        return session.queryList(FILE_SELECT + "where f.root_id = ? and f.status = ?",
                SqliteVfsCatalog::mapFile, rootId.value(), VfsFileStatus.MISSING.name());
    }

    /**
     * Moves a snapshot row to a new path in place, keeping its file id.
     * Must recompute {@code file_name_sort}: a stale {@link Collator} key silently breaks {@code order by}.
     */
    private VfsManagedFile relocate(DatabaseSession session, VfsManagedFile before, ScannedEntry entry) {
        String fileName = fileNameOf(entry.relativePath());
        session.update("""
                update jdesk_vfs_files
                set relative_path = ?, parent_path = ?, file_name = ?, file_name_sort = ?,
                    status = ?, size = ?, modified_at = ?, file_created_at = ?,
                    content_version = content_version + 1, updated_at = ?
                where file_id = ?
                """,
                entry.relativePath(),
                parentPathOf(entry.relativePath()),
                fileName,
                VfsFileNameSort.key(collator, fileName),
                VfsFileStatus.PRESENT.name(),
                entry.size(),
                entry.modifiedAt(),
                entry.createdAt(),
                now(),
                before.fileId().value());
        return findFile(session, before.fileId())
                .orElseThrow(() -> new StorageException(
                        "VFS file disappeared after relocate: " + before.fileId().value()));
    }

    /** Returns the stored native id, or an empty string, which keeps the row out of pairing. */
    private static String storedNativeId(DatabaseSession session, VfsFileId fileId) {
        return session.queryOne("select native_file_id from jdesk_vfs_files where file_id = ?",
                row -> row.getString("native_file_id"), fileId.value()).orElse("");
    }

    /**
     * Stores a native id, writing nothing when it is unchanged. Don't let an empty id overwrite a stored one: empty
     * means the clue is briefly unavailable, not that the identity changed.
     */
    private static void refreshNativeId(DatabaseSession session, VfsFileId fileId, String nativeFileId) {
        if (nativeFileId == null || nativeFileId.isEmpty()) {
            return;
        }
        session.update("update jdesk_vfs_files set native_file_id = ? "
                + "where file_id = ? and native_file_id <> ?", nativeFileId, fileId.value(), nativeFileId);
    }

    private VfsManagedFile markStatus(DatabaseSession session, VfsFileId fileId, VfsFileStatus status) {
        session.update("update jdesk_vfs_files set status = ?, content_version = content_version + 1, "
                        + "updated_at = ? where file_id = ?",
                status.name(), now(), fileId.value());
        return findFile(session, fileId)
                .orElseThrow(() -> new StorageException("VFS file disappeared after status mark: " + fileId.value()));
    }

    private static List<VfsManagedFile> loadScope(DatabaseSession session, VfsRootId rootId,
                                                  String baseRel, boolean recursive, Map<VfsFileId, String> nativeSnapshot) {
        com.jdesk.frame.storage.dao.RowMapper<VfsManagedFile> mapper = row -> {
            VfsManagedFile file = mapFile(row);
            nativeSnapshot.put(file.fileId(), row.getString("native_file_id"));
            return file;
        };
        if (!recursive) {
            return session.queryList(FILE_SELECT + "where f.root_id = ? and f.parent_path = ?",
                    mapper, rootId.value(), baseRel);
        }
        if (baseRel.isEmpty()) {
            return session.queryList(FILE_SELECT + "where f.root_id = ?",
                    mapper, rootId.value());
        }
        String likePrefix = likeEscape(baseRel) + "/%";
        return session.queryList(
                FILE_SELECT + "where f.root_id = ? and f.relative_path like ? escape '\\'",
                mapper, rootId.value(), likePrefix);
    }

    private static String likeEscape(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * Returns the PRESENT rows this pass didn't see, to be marked {@link VfsFileStatus#MISSING}: none if the scan was
     * incomplete, and none under {@code incompleteDirs} (relative paths, the empty string being the root).
     */
    static List<VfsManagedFile> detectMissing(List<VfsManagedFile> snapshot, Set<String> seen, boolean scanComplete,
                                              Set<String> incompleteDirs) {
        if (!scanComplete) {
            return List.of();
        }
        return snapshot.stream()
                .filter(f -> f.status() == VfsFileStatus.PRESENT && !seen.contains(f.relativePath())
                        && !underAny(f.relativePath(), incompleteDirs))
                .toList();
    }

    private static boolean underAny(String relativePath, Set<String> dirs) {
        if (dirs.isEmpty()) {
            return false;
        }
        String parent = relativePath;
        do {
            parent = parentPathOf(parent);
            if (dirs.contains(parent)) {
                return true;
            }
        } while (!parent.isEmpty());
        return false;
    }

    /**
     * Result of a directory walk.
     *
     * @param entries        entries whose attributes were read; they are reconciled
     * @param unreadable     entries that exist but whose attributes couldn't be read (antivirus, locks, permissions);
     *                       not reconciled, but counted as seen
     * @param complete       whether the walk finished; if not, no row is marked MISSING
     * @param incompleteDirs subtrees not fully walked, whose rows aren't marked MISSING in this pass
     */
    private record ScanEntries(List<ScannedEntry> entries, Set<String> unreadable, boolean complete,
                               Set<String> incompleteDirs) {
    }

    /**
     * Walks {@code baseDir}, recursively or one level, collecting entries with a cancellation checkpoint before each.
     * Must use checkpointUnchecked: the catch below would commit an InterruptedIOException as an incomplete scan.
     */
    private ScanEntries walkEntries(Path rootPath, Path baseDir, boolean recursive, ScanFilter filter) {
        List<ScannedEntry> entries = new ArrayList<>();
        Set<String> unreadable = new LinkedHashSet<>();
        Set<String> incompleteDirs = new HashSet<>();
        String doing = "scanning " + baseDir;
        // Child name to native id per directory, read on entry and dropped on exit; don't open a handle per file.
        Map<Path, Map<String, String>> identities = new HashMap<>();
        // Links (FileLinks) are neither followed nor listed, nor counted as seen.
        try {
            if (recursive) {
                Files.walkFileTree(baseDir, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        Cancellation.checkpointUnchecked(doing);
                        if (dir.equals(baseDir)) {
                            prefetchIdentities(identities, dir);
                            return FileVisitResult.CONTINUE;
                        }
                        if (!filter.include(dir, true) || FileLinks.isLink(dir, attrs)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        // Its own id comes from the parent's table; then prefetch one for its children.
                        entries.add(toEntry(rootPath, dir, true, attrs, identities));
                        prefetchIdentities(identities, dir);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                        identities.remove(dir); // Subtree done; drop its table.
                        if (exc != null) {
                            LOG.warn("VFS scan stopped partway through {}", dir, exc);
                            incompleteDirs.add(relativize(rootPath, dir));
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        Cancellation.checkpointUnchecked(doing);
                        if (filter.include(file, false) && !FileLinks.isLink(file, attrs)) {
                            entries.add(toEntry(rootPath, file, false, attrs, identities));
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        Cancellation.checkpointUnchecked(doing);
                        // Not the base: a root reached via a junction would otherwise make the whole tree vanish.
                        if (!file.equals(baseDir) && FileLinks.isLink(file)) {
                            return FileVisitResult.CONTINUE; // Unopenable link (My Music in Documents): absent.
                        }
                        // May be an unopenable directory: count it as seen and mark nothing under it MISSING.
                        LOG.warn("VFS scan skipped unreadable entry {}", file, exc);
                        String relative = relativize(rootPath, file);
                        unreadable.add(relative);
                        incompleteDirs.add(relative);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } else {
                Cancellation.checkpointUnchecked(doing);
                prefetchIdentities(identities, baseDir);
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(baseDir)) {
                    for (Path child : stream) {
                        Cancellation.checkpointUnchecked(doing);
                        boolean directory = Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS);
                        if (!filter.include(child, directory)) {
                            continue;
                        }
                        try {
                            BasicFileAttributes attrs = Files.readAttributes(child, BasicFileAttributes.class,
                                    LinkOption.NOFOLLOW_LINKS);
                            if (FileLinks.isLink(child, attrs)) {
                                continue;
                            }
                            entries.add(toEntry(rootPath, child, directory, attrs, identities));
                        } catch (IOException e) {
                            // As in visitFileFailed: one unreadable entry mustn't stop the directory walk.
                            LOG.warn("VFS scan skipped unreadable entry {}", child, e);
                            unreadable.add(relativize(rootPath, child));
                        }
                    }
                }
            }
        } catch (IOException | DirectoryIteratorException e) {
            // The listing itself failed: report an incomplete scan so detectMissing marks nothing this pass.
            LOG.warn("VFS scan failed under {}", baseDir, e);
            return new ScanEntries(entries, unreadable, false, incompleteDirs);
        }
        return new ScanEntries(entries, unreadable, true, incompleteDirs);
    }

    /** Returns the root-relative path with forward slashes, as {@link #toEntry} stores it. */
    private static String relativize(Path rootPath, Path entry) {
        return rootPath.relativize(entry).toString().replace('\\', '/');
    }

    private ScannedEntry toEntry(Path rootPath, Path entry, boolean directory, BasicFileAttributes attrs,
                                 Map<Path, Map<String, String>> identities) {
        String rel = relativize(rootPath, entry);
        long size = directory ? 0 : attrs.size();
        return new ScannedEntry(rel, directory, size,
                attrs.lastModifiedTime().toMillis(), attrs.creationTime().toMillis(),
                nativeIdOf(entry, identities));
    }

    /** Reads a directory's native id table on entry; an unreadable one stays empty and lookups fall back per file. */
    private void prefetchIdentities(Map<Path, Map<String, String>> identities, Path directory) {
        identities.computeIfAbsent(directory, nativeFileIds::identitiesIn);
    }

    /**
     * Looks the entry up in its directory's prefetched table, then asks for it alone.
     * Keep the fallback: the table is empty without platform support and misses entries created after it was read.
     */
    private String nativeIdOf(Path entry, Map<Path, Map<String, String>> identities) {
        Path parent = entry.getParent();
        if (parent != null) {
            Map<String, String> table = identities.get(parent);
            if (table != null) {
                String identity = table.get(entry.getFileName().toString());
                if (identity != null) {
                    return identity;
                }
            }
        }
        return nativeIdOf(entry);
    }

    /** Returns an empty string when unavailable, meaning "no clue", not "a different identity". */
    private String nativeIdOf(Path path) {
        return nativeFileIds.identityOf(path).orElse("");
    }

    /** Returns whether the file with the old id was deleted or moved out of the root; unknown counts as inside. */
    private boolean originalLeftRoot(String previousNativeId, Path currentPath, Path rootPath) {
        Path hint = currentPath.getParent() != null ? currentPath.getParent() : rootPath;
        NativeFileIdReader.Location location = nativeFileIds.locate(previousNativeId, hint);
        if (location.kind() == NativeFileIdReader.Location.Kind.GONE) {
            return true;
        }
        if (location.kind() != NativeFileIdReader.Location.Kind.AT) {
            return false;
        }
        try {
            return !location.path().toRealPath().startsWith(rootPath.toRealPath());
        } catch (IOException vanished) {
            return false;
        }
    }

    private record ScannedEntry(String relativePath, boolean directory, long size, long modifiedAt,
                                long createdAt, String nativeFileId) {
    }

    /**
     * Marks this catalog closed. The supplied database handle remains under the
     * caller's control.
     */
    @Override
    public void close() {
        closed = true;
    }

    private void ensureSchema() {
        database.migrate(VfsCatalogSchema.NAMESPACE, VfsCatalogSchema.migrations());
    }

    private VfsRootId insertRoot(DatabaseSession session, String rootKey, String name, Path rootPath) {
        VfsRootId rootId = VfsRootId.of(UUID.randomUUID().toString());
        long now = now();
        session.update("""
                insert into jdesk_vfs_roots(root_id, root_key, name, root_path, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?)
                """, rootId.value(), rootKey, name, rootPath, now, now);
        return rootId;
    }

    private VfsManagedFile insertFile(DatabaseSession session,
                                      VfsRootId rootId,
                                      String relativePath,
                                      boolean directory) {
        VfsRoot root = findRoot(session, rootId)
                .orElseThrow(() -> new StorageException("VFS root not found: " + rootId.value()));
        Path absolutePath = root.rootPath().resolve(relativePath).toAbsolutePath().normalize();
        BasicFileState state = readFileState(root.rootPath(), absolutePath, directory);
        return insertRow(session, rootId, relativePath, directory, state.status(), state.size(),
                state.modifiedAt(), state.createdAt(), nativeIdOf(absolutePath));
    }

    private VfsManagedFile insertRow(DatabaseSession session, VfsRootId rootId, String relativePath,
                                     boolean directory, VfsFileStatus status, long size, long modifiedAt,
                                     long fileCreatedAt, String nativeFileId) {
        VfsFileId fileId = VfsFileId.of(UUID.randomUUID().toString());
        String fileName = fileNameOf(relativePath);
        long now = now();
        session.update("""
                insert into jdesk_vfs_files(
                  file_id, root_id, relative_path, parent_path, file_name, file_name_sort,
                  status, directory, size, modified_at, file_created_at,
                  content_version, created_at, updated_at, native_file_id, read_only
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                fileId.value(),
                rootId.value(),
                relativePath,
                parentPathOf(relativePath),
                fileName,
                VfsFileNameSort.key(collator, fileName),
                status.name(),
                directory,
                size,
                modifiedAt,
                fileCreatedAt,
                1,
                now,
                now,
                nativeFileId == null ? "" : nativeFileId,
                false);
        return findFile(session, fileId)
                .orElseThrow(() -> new StorageException("VFS file disappeared after insert: " + fileId.value()));
    }

    /**
     * Standard file-row projection, joining roots for {@code root_path} so {@link #mapFile} can build absolute paths.
     * Columns in appended where clauses must carry the {@code f.} prefix: both tables have {@code root_id}.
     */
    private static final String FILE_SELECT = """
            select f.file_id, f.root_id, f.relative_path, r.root_path, f.status, f.directory,
                   f.size, f.modified_at, f.file_created_at, f.content_version, f.native_file_id, f.read_only
            from jdesk_vfs_files f join jdesk_vfs_roots r on r.root_id = f.root_id
            """;

    private static String parentPathOf(String relativePath) {
        int idx = relativePath.lastIndexOf('/');
        return idx < 0 ? "" : relativePath.substring(0, idx);
    }

    private static String fileNameOf(String relativePath) {
        int idx = relativePath.lastIndexOf('/');
        return idx < 0 ? relativePath : relativePath.substring(idx + 1);
    }

    private static Optional<VfsRoot> findRoot(DatabaseSession session, VfsRootId rootId) {
        return session.queryOne("""
                select root_id, name, root_path
                from jdesk_vfs_roots
                where root_id = ?
                """, SqliteVfsCatalog::mapRoot, rootId.value());
    }

    private static Optional<VfsRoot> findRootByKey(DatabaseSession session, String rootKey) {
        return session.queryOne("""
                select root_id, name, root_path
                from jdesk_vfs_roots
                where root_key = ?
                """, SqliteVfsCatalog::mapRoot, rootKey);
    }

    private static Optional<VfsManagedFile> findFile(DatabaseSession session, VfsFileId fileId) {
        return session.queryOne(FILE_SELECT + "where f.file_id = ?",
                SqliteVfsCatalog::mapFile, fileId.value());
    }

    private static Optional<VfsManagedFile> findFile(DatabaseSession session, VfsRootId rootId, String relativePath) {
        return session.queryOne(FILE_SELECT + "where f.root_id = ? and f.relative_path = ?",
                SqliteVfsCatalog::mapFile, rootId.value(), relativePath);
    }

    private static VfsRoot mapRoot(DatabaseRow row) {
        return new VfsRoot(
                VfsRootId.of(row.getString("root_id")),
                row.getString("name"),
                row.getPath("root_path")
        );
    }

    /**
     * Maps a file row, deriving the absolute path from {@code root_path} and {@code relative_path} instead of storing
     * it, so a moved root needs no rewrite. If you change how it resolves, check {@link #insertFile} and
     * {@link #moveFile}.
     */
    private static VfsManagedFile mapFile(DatabaseRow row) {
        String relativePath = row.getString("relative_path");
        return new VfsManagedFile(
                VfsFileId.of(row.getString("file_id")),
                VfsRootId.of(row.getString("root_id")),
                relativePath,
                row.getPath("root_path").resolve(relativePath).toAbsolutePath().normalize(),
                VfsFileStatus.valueOf(row.getString("status")),
                row.getBoolean("directory"),
                row.getLong("size"),
                row.getLong("modified_at"),
                row.getLong("content_version"),
                row.getLong("file_created_at"),
                row.getBoolean("read_only")
        );
    }

    /** Reads a path's state; a path reached through a link is MISSING, as in the walk ({@link FileLinks}). */
    private static BasicFileState readFileState(Path rootPath, Path path, boolean directory) {
        if (!Files.exists(path) || FileLinks.reachedThroughLink(rootPath, path)) {
            return new BasicFileState(VfsFileStatus.MISSING, 0, 0, 0);
        }
        try {
            // One readAttributes call for size, mtime and creation time.
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            long size = directory ? 0 : attrs.size();
            return new BasicFileState(VfsFileStatus.PRESENT, size,
                    attrs.lastModifiedTime().toMillis(), attrs.creationTime().toMillis());
        } catch (IOException e) {
            return new BasicFileState(VfsFileStatus.MISSING, 0, 0, 0);
        }
    }

    private void checkOpen() {
        if (closed) {
            throw new StorageException("VFS catalog is closed: " + scopeId);
        }
    }

    private static long now() {
        return Instant.now().toEpochMilli();
    }

    private static String requireText(String label, String value) {
        String cleaned = Objects.requireNonNull(value, label).trim();
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return cleaned;
    }

    private record BasicFileState(VfsFileStatus status, long size, long modifiedAt, long createdAt) {
    }
}
