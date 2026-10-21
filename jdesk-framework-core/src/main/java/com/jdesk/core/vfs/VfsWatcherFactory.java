package com.jdesk.core.vfs;

/** Creates the global watcher, which watches no root until {@link VfsWatcher#setRoots} is called. */
@FunctionalInterface
public interface VfsWatcherFactory {

    /**
     * Creates a watcher. {@code filter} is checked for every raw event, so pass one that dispatches to each root's own
     * {@link ScanFilter}: events in excluded directories are then dropped before debouncing. {@code listener} receives
     * the debounced changes and overflows.
     */
    VfsWatcher create(ScanFilter filter, VfsWatchListener listener);
}
