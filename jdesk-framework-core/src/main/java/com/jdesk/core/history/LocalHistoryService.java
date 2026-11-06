package com.jdesk.core.history;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Application service that opens and closes local history stores by scope. When a scope has the same name as a
 * managed VFS catalog scope, the VFS service feeds that scope's changes into the store.
 */
public interface LocalHistoryService {

    /**
     * Opens or reuses the same directory and history policy; defaults create or recover storage and return an unavailable store on failure.
     * An existing-root path scope requires prepared history and throws on failure; queued work retains that scope.
     * An ordinary reopen adopts future maintenance and close work without relaxing already queued operations.
     */
    LocalHistoryStore openStore(String scopeId, Path historyDir, HistoryPolicy policy);

    /**
     * Opens like the three-argument form and, if the implementation supports it, leaves a copy of the history database
     * in {@code preMigrationCopyDir} before its pending migrations, throwing if it can't. {@code null} leaves none.
     * The folder doesn't count when deciding whether a scope is already open with the same directory and policy.
     */
    default LocalHistoryStore openStore(String scopeId, Path historyDir, HistoryPolicy policy,
                                        Path preMigrationCopyDir) {
        return openStore(scopeId, historyDir, policy);
    }

    Optional<LocalHistoryStore> findOpenStore(String scopeId);

    /** Seals all head versions and closes the store; returns false if it isn't open. */
    boolean closeStore(String scopeId);
}
