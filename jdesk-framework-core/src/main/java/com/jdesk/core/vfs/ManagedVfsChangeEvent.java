package com.jdesk.core.vfs;

import java.util.Objects;

/**
 * One catalog change batch; registered changes were explicitly published after the writer changed the catalog.
 * Subscribers receive the whole batch, never one call per file.
 */
public record ManagedVfsChangeEvent(String scopeId, VfsScanResult changes, boolean registered) {
    /** Creates a legacy notification without enabling registered-change handling. */
    public ManagedVfsChangeEvent(String scopeId, VfsScanResult changes) {
        this(scopeId, changes, false);
    }
    public ManagedVfsChangeEvent {
        scopeId = Objects.requireNonNull(scopeId, "scopeId");
        changes = Objects.requireNonNull(changes, "changes");
    }
}
