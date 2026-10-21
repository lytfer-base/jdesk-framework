package com.jdesk.frame.storage;

/**
 * A scoped database transaction.
 *
 * <p>The transaction uses the same backend connection for all operations in the
 * callback. The owning handle controls commit and rollback; callers should not
 * commit through an unwrapped connection.
 */
public interface DatabaseTransaction extends DatabaseSession {
}
