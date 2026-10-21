package com.jdesk.core.vfs;

/**
 * {@link com.jdesk.core.message.MessageBus} topic for batched managed VFS changes: one call per refresh with all its
 * added, updated and missing rows. Calls run synchronously on the publishing thread, so UI listeners must move to
 * the EDT and refresh once per batch. Batches from reconciliation arrive in order per scope; batches from
 * {@code notifyChanged} hold only added and updated rows and aren't part of that order.
 */
@FunctionalInterface
public interface ManagedVfsChangeListener {
    void onManagedVfsChanged(ManagedVfsChangeEvent event);
}
