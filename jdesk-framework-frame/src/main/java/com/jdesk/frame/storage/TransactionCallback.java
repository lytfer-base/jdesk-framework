package com.jdesk.frame.storage;

/**
 * Callback executed inside a scoped {@link DatabaseTransaction}.
 *
 * <p>The transaction is committed when this method returns normally and rolled
 * back when it throws a runtime exception or error.
 */
@FunctionalInterface
public interface TransactionCallback<T> {

    /**
     * Runs transactional database work.
     */
    T run(DatabaseTransaction transaction);
}
