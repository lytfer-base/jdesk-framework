package com.jdesk.core.vfs;

/**
 * Lifecycle state of a managed file record. There is no deleted state: deleting a file removes its row.
 */
public enum VfsFileStatus {
    /**
     * The file is known and currently present at its catalog path.
     */
    PRESENT,

    /**
     * The catalog record is kept, but the file is temporarily unavailable.
     */
    MISSING
}
