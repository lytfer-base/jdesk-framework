package com.jdesk.core.vfs;

import java.nio.file.Path;
import java.util.Set;

/**
 * Debounced callbacks of a {@link VfsWatcher}. Don't merge the two methods: a change directly under a root must not
 * be mistaken for an overflow, which triggers a full recursive rescan.
 */
public interface VfsWatchListener {

    /** Receives the directories (absolute) affected in the debounce window; reconcile each one non-recursively. */
    void changed(Set<Path> affectedDirs);

    /** Reports an overflow of the OS event queue, so events may be lost; rescan these roots recursively. */
    void overflow(Set<Path> roots);
}
