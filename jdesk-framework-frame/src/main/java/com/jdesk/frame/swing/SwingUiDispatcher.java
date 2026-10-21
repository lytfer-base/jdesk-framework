package com.jdesk.frame.swing;

import javax.swing.SwingUtilities;

/** Swing EDT dispatch helper for frame UI code: runs work at once on the EDT and posts it there otherwise. */
public class SwingUiDispatcher {

    /**
     * Runs the task on the EDT: right away when called there, otherwise through
     * {@link SwingUtilities#invokeLater(Runnable)}.
     */
    public void invokeLater(Runnable runnable) {
        if (isDispatchThread()) {
            runnable.run();
        } else {
            SwingUtilities.invokeLater(runnable);
        }
    }

    /** Returns whether the current thread is the EDT. */
    public boolean isDispatchThread() {
        return SwingUtilities.isEventDispatchThread();
    }

    /** Throws IllegalStateException unless called on the EDT. */
    public void assertDispatchThread() {
        if (!isDispatchThread()) {
            throw new IllegalStateException(
                    "Must be called on EDT. Current thread: " + Thread.currentThread().getName());
        }
    }
}
