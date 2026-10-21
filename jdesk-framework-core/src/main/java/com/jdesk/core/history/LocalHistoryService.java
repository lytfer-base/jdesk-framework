package com.jdesk.core.history;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Application service that opens and closes local history stores by scope. When a scope has the same name as a
 * managed VFS catalog scope, the VFS service feeds that scope's changes into the store.
 */
public interface LocalHistoryService {

    /**
     * Opens or returns the store, creating its directory if needed; if it can't be opened, returns an unavailable
     * store instead of throwing.
     */
    LocalHistoryStore openStore(String scopeId, Path historyDir, HistoryPolicy policy);

    Optional<LocalHistoryStore> findOpenStore(String scopeId);

    /** Seals all head versions and closes the store; returns false if it isn't open. */
    boolean closeStore(String scopeId);
}
