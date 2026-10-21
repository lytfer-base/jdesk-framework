package com.jdesk.core.history;

/**
 * Where a version came from. Folding happens only within {@link #APP} and {@link #EXTERNAL}; every other source gets
 * a row of its own that is sealed at once.
 */
public enum HistorySource {
    /** The application wrote the file. */
    APP,
    /** An outside change found by the watcher or by reconciliation. */
    EXTERNAL,
    /** A write made by restoring from history. */
    RESTORE,
    /** The baseline taken when a file first comes under management. */
    BASELINE,
    /** An explicit "save a version now" by the user. */
    SNAPSHOT
}
