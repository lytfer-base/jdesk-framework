package com.jdesk.concurrency;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Application-level read/write lock for protecting shared mutable state.
 * Inspired by IntelliJ Platform's Application lock model.
 *
 * <p>Built on {@link ReentrantReadWriteLock} with fair mode (writers get priority).
 * Fair mode ensures that when a writer is waiting, new readers queue behind the
 * writer — preventing write starvation without manual write-intent tracking.
 *
 * <h3>Lock levels:</h3>
 * <ul>
 *   <li><b>Read</b> — Multiple readers can hold simultaneously. Writers block until
 *       all readers release.</li>
 *   <li><b>Write</b> — Exclusive access. Requests queue behind existing readers
 *       and block new readers (fair mode).</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * appLock.read(() -> {
 *     // read shared state
 * });
 *
 * appLock.write(() -> {
 *     // modify shared state — exclusive
 * });
 * }</pre>
 */
public class AppLock {

    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock(true);

    /**
     * Execute an action under a read lock.
     * Multiple readers may hold the lock concurrently.
     * If a writer is queued (fair mode), new readers wait behind it.
     */
    public void read(Runnable action) {
        rwLock.readLock().lock();
        try {
            action.run();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Execute an action under a write lock (exclusive).
     * Waits for all existing readers to finish, then blocks new readers
     * until the write completes.
     */
    public void write(Runnable action) {
        rwLock.writeLock().lock();
        try {
            action.run();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Check if a write lock is currently held.
     */
    public boolean isWriteInProgress() {
        return rwLock.isWriteLocked();
    }

    /**
     * Check if any read locks are currently held.
     */
    public boolean isReadInProgress() {
        return rwLock.getReadLockCount() > 0;
    }
}
