package com.jdesk.core.vfs;

import java.nio.file.Path;

/**
 * Hook that decides during a scan whether an entry goes into the snapshot. A rejected directory is skipped with its
 * whole subtree; {@link #all()} accepts everything, and applications can use it to skip their own metadata folders
 * or {@code .git}.
 */
@FunctionalInterface
public interface ScanFilter {

    /** Returns whether to include the entry at the absolute {@code path}. */
    boolean include(Path path, boolean directory);

    /** Returns a filter that accepts everything. */
    static ScanFilter all() {
        return (path, directory) -> true;
    }
}
