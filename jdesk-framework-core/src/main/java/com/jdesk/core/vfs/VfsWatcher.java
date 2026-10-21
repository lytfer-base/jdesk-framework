package com.jdesk.core.vfs;

import com.jdesk.core.disposer.Disposable;

import java.nio.file.Path;
import java.util.Set;

/**
 * Watches a set of root subtrees for file system changes and does nothing else: no catalog, refresh or publishing.
 * One instance watches all registered roots, like IntelliJ's application-level FileWatcher, so its thread count
 * doesn't grow with the number of roots; {@code dispose()} stops watching and releases the threads.
 */
public interface VfsWatcher extends Disposable {

    /**
     * Replaces the whole set of watched roots; implementations rebuild the underlying watch, which can't add or remove
     * paths at runtime. An empty set watches nothing but keeps the threads; an unchanged set must be a no-op.
     */
    void setRoots(Set<Path> roots);
}
