package com.jdesk.frame.storage;

/**
 * Base runtime exception for frame storage failures.
 *
 * <p>The storage layer normalizes backend exceptions to this type so callers do
 * not need to handle JDBC-specific checked exceptions.
 */
public class StorageException extends RuntimeException {

    /**
     * Creates an exception with a user/debug-readable storage message.
     */
    public StorageException(String message) {
        super(message);
    }

    /**
     * Creates an exception that wraps the backend or mapping failure cause.
     */
    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
