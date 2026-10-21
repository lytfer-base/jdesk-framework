package com.jdesk.frame.ui;

/**
 * Why a standalone window closes; the frame tells only an ordinary close from application exit, with no
 * application-specific meaning.
 */
public enum JdfFrameCloseReason {
    /** Ordinary close by the user or the window system. */
    WINDOW_CLOSE,
    /** Close during application exit. */
    APPLICATION_EXIT
}
