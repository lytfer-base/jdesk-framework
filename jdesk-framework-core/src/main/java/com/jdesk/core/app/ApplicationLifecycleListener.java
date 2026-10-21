package com.jdesk.core.app;

/**
 * Listener for application lifecycle events. Events go first to listeners registered directly on {@link Application},
 * and also to message bus subscribers once the application {@link com.jdesk.core.message.MessageBus} exists.
 */
public interface ApplicationLifecycleListener {

    /**
     * Called when {@link Application#init()} starts, before the framework configuration, core services and plugins
     * load; only listeners registered with {@link Application#addLifecycleListener} receive it reliably.
     */
    default void beforeApplicationLoaded(Application app) {
    }

    /** Called once the framework and the plugin descriptors are initialized, before {@link Application#start()}. */
    default void applicationInitialized(Application app) {
    }

    /**
     * Called in {@link Application#start()} after plugins start and before the RUNNING state: the last framework hook
     * before the UI shows its top-level windows.
     */
    default void beforeUiShown(Application app) {
    }

    /** Called once the application has fully started and is running. */
    default void onApplicationStarted(Application app) {
    }

    /**
     * Called after shutdown is requested and before any irreversible work, for example to ask for confirmation
     * before {@link #canExitApplication(Application)}.
     */
    default void appClosing(Application app) {
        onApplicationClosing(app);
    }

    /** Alias of {@link #appClosing(Application)}, which forwards here by default. */
    default void onApplicationClosing(Application app) {
    }

    /** Returns {@code false} to cancel this shutdown request; the application keeps running if any listener refuses. */
    default boolean canExitApplication(Application app) {
        return true;
    }

    /** Called once shutdown is allowed and before resources are released; persist settings and other state here. */
    default void onApplicationSaving(Application app) {
    }

    /** Called after saving and the final exit check, before the irreversible shutdown phase. */
    default void appWillBeClosed(Application app) {
        onApplicationWillClose(app);
    }

    /** Alias of {@link #appWillBeClosed(Application)}, which forwards here by default. */
    default void onApplicationWillClose(Application app) {
    }

    /** Called once the irreversible shutdown phase begins; don't try to cancel the shutdown from here. */
    default void onApplicationShuttingDown(Application app) {
    }
}
