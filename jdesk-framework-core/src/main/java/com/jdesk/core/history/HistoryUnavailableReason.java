package com.jdesk.core.history;

/**
 * Why history is unavailable. The framework provides only the reason code; the application maps {@link #code()} to
 * UI text, and technical details go to the log only.
 */
public enum HistoryUnavailableReason {
    /** The history directory can't be created or written. */
    DIRECTORY_UNAVAILABLE("directoryUnavailable"),
    /** The store is intact but can't be opened for now because of the disk, permissions or the like. */
    TEMPORARILY_UNAVAILABLE("temporarilyUnavailable"),
    /** The store still can't be opened after a corrupted one was set aside and rebuilt. */
    OPEN_FAILED("openFailed");

    private final String code;

    HistoryUnavailableReason(String code) {
        this.code = code;
    }

    /** Returns the camelCase reason code sent to pages; if you change a code, update the application's text keys. */
    public String code() {
        return code;
    }
}
