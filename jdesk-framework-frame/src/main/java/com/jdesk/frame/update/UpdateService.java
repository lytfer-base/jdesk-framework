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

    /** Registers a listener called, on any thread, when {@link #view()} changes; it stays until the service is disposed. */
    void addViewListener(Runnable listener);

    /** Runs the action behind the current {@link UpdateView#menuLabel()}; called on the EDT, must return at once. */
    void runMenuAction();

    /** Starts an update check now; called on the EDT, must return at once. */
    void checkNow();

    /** Turns automatic downloading on or off; callable on any thread. */
    void setAutoDownload(boolean enabled);
}
