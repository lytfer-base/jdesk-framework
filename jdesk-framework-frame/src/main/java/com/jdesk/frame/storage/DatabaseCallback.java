package com.jdesk.frame.storage;

/**
 * Callback executed inside a scoped {@link DatabaseSession}.
 *
 * <p>The session must not be stored after the callback returns.
 */
@FunctionalInterface
public interface DatabaseCallback<T> {

    /**
     * Runs database work inside the session supplied by a {@link DatabaseHandle}.
     */
    T run(DatabaseSession session);
}
