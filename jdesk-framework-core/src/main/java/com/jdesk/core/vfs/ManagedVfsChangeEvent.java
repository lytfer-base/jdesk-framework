package com.jdesk.core.vfs;

import java.util.Objects;

/**
 * All changes from one {@link VfsCatalog#refresh} as a single event; {@link #changes()} holds the whole
 * {@link VfsScanResult}. Subscribers get the batch at once, never one call per file.
 */
public record ManagedVfsChangeEvent(String scopeId, VfsScanResult changes) {
    public ManagedVfsChangeEvent {
        scopeId = Objects.requireNonNull(scopeId, "scopeId");
        changes = Objects.requireNonNull(changes, "changes");
    }
}
