package com.jdesk.core.app;

import java.util.Objects;

/**
 * Global access point to the running {@link Application}. It only holds the process-wide instance and neither creates
 * nor shuts it down.
 */
public final class ApplicationManager {

    private static volatile Application application;

    private ApplicationManager() {
    }

    /**
     * Returns the current application.
     *
     * @throws IllegalStateException if no application has been set
     */
    public static Application getApplication() {
        Application app = application;
        if (app == null) {
            throw new IllegalStateException("Application has not been initialized");
        }
        return app;
    }

    /** Returns the current application, or {@code null} if none has been set. */
    public static Application getApplicationIfCreated() {
        return application;
    }

    /** Installs the application during startup; throws IllegalStateException if another live one is installed. */
    public static void setApplication(Application app) {
        Objects.requireNonNull(app, "app");
        Application existing = application;
        if (existing != null && existing != app && !existing.isDisposed()) {
            throw new IllegalStateException("Application is already initialized: " + existing);
        }
        application = app;
    }

    /** Clears the current application if it is {@code app}. */
    public static void clearApplication(Application app) {
        if (application == app) {
            application = null;
        }
    }
}
