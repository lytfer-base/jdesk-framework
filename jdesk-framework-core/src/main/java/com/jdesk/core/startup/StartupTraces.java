package com.jdesk.core.startup;

import com.jdesk.core.app.Application;
import com.jdesk.core.app.ApplicationManager;
import com.jdesk.core.service.ServiceContainer;

/**
 * Helper for retrieving the current application's startup trace without forcing
 * service creation.
 */
public final class StartupTraces {

    private StartupTraces() {
    }

    public static StartupTrace getCurrent() {
        Application app = ApplicationManager.getApplicationIfCreated();
        if (app == null || app.isDisposed()) {
            return StartupTrace.disabled();
        }
        ServiceContainer serviceContainer = app.getServiceContainer();
        if (!serviceContainer.hasService(StartupTrace.class)) {
            return StartupTrace.disabled();
        }
        StartupTrace trace = serviceContainer.getServiceIfCreated(StartupTrace.class);
        return trace == null ? StartupTrace.disabled() : trace;
    }

    public static StartupSpan begin(String name) {
        return getCurrent().begin(name);
    }
}
