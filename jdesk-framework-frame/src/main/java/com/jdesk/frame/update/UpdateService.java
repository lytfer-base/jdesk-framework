package com.jdesk.frame.update;

import com.jdesk.core.disposer.Disposable;

/**
 * The self-update component of an application, found through {@link UpdateServices}. It owns every user-visible
 * text about updating and hands them to the application as an {@link UpdateView}.
 */
public interface UpdateService extends Disposable {

    /** Starts the service with the host's capabilities; called once, on any thread. */
    void start(UpdateHost host);

    /** Returns the current texts and state; callable on any thread. */
    UpdateView view();

    /** Registers a listener to run, on any thread, when {@link #view()} changes; callable on any thread. */
    void addViewListener(Runnable listener);

    /**
     * Removes a listener registered with {@link #addViewListener}; callable on any thread.
     * Removing a listener that was never added does nothing.
     */
    void removeViewListener(Runnable listener);

    /** Runs the action behind the current {@link UpdateView#menuLabel()}; called on the EDT, must return at once. */
    void runMenuAction();

    /** Starts an update check now; called on the EDT, must return at once. */
    void checkNow();

    /** Turns automatic downloading on or off; callable on any thread. */
    void setAutoDownload(boolean enabled);
}
