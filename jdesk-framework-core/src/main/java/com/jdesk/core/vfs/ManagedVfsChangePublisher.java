package com.jdesk.core.vfs;

import com.jdesk.core.app.Application;
import com.jdesk.core.app.ApplicationManager;

import java.util.Objects;

/**
 * Publishes the {@link VfsScanResult} of one refresh as a single batch event on the {@link ManagedVfsChangeListener}
 * topic of the application message bus; the catalog only returns the diff, and callers publish it after a refresh.
 * Delivery is synchronous and isolates each subscriber's exceptions.
 * Don't cache the {@code MessageBus}; look up the application through {@link ApplicationManager} on each publish.
 */
public final class ManagedVfsChangePublisher {

    /** Publishes the diff as one batch event; skips an empty diff and a missing or disposed application. */
    public void publish(String scopeId, VfsScanResult changes) {
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(changes, "changes");
        if (changes.added().isEmpty() && changes.updated().isEmpty() && changes.missing().isEmpty()) {
            return;
        }
        Application app = ApplicationManager.getApplicationIfCreated();
        if (app == null || app.isDisposed()) {
            return;
        }
        app.getMessageBus()
           .syncPublisher(ManagedVfsChangeListener.class)
           .onManagedVfsChanged(new ManagedVfsChangeEvent(scopeId, changes));
    }
}
