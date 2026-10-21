package com.jdesk.frame.storage;

import java.time.Duration;

/**
 * Runtime options for a managed database.
 *
 * <p>The defaults favor desktop-app responsiveness: WAL is enabled,
 * synchronous mode is {@link Synchronous#NORMAL}, a small busy timeout is set,
 * and foreign keys are disabled to match the framework's weak-constraint data
 * strategy.
 */
public final class DatabaseOptions {

    private final boolean wal;
    private final boolean foreignKeys;
    private final Duration busyTimeout;
    private final Synchronous synchronous;

    private DatabaseOptions(Builder builder) {
        this.wal = builder.wal;
        this.foreignKeys = builder.foreignKeys;
        this.busyTimeout = builder.busyTimeout;
        this.synchronous = builder.synchronous;
    }

    /**
     * Returns the recommended default option set for frame-managed SQLite files.
     */
    public static DatabaseOptions defaults() {
        return builder().build();
    }

    /**
     * Starts a mutable builder initialized with the default option values.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns whether SQLite WAL journal mode should be enabled.
     */
    public boolean wal() {
        return wal;
    }

    /**
     * Returns whether SQLite foreign-key enforcement should be enabled.
     */
    public boolean foreignKeys() {
        return foreignKeys;
    }

    /**
     * Returns how long SQLite should wait when another connection holds a lock.
     */
    public Duration busyTimeout() {
        return busyTimeout;
    }

    /**
     * Returns the SQLite synchronous durability/performance mode.
     */
    public Synchronous synchronous() {
        return synchronous;
    }

    /**
     * Supported values for {@code PRAGMA synchronous}.
     */
    public enum Synchronous {
        OFF, NORMAL, FULL
    }

    public static final class Builder {
        private boolean wal = true;
        private boolean foreignKeys;
        private Duration busyTimeout = Duration.ofSeconds(5);
        private Synchronous synchronous = Synchronous.NORMAL;

        /**
         * Enables or disables {@code PRAGMA journal_mode=WAL}.
         */
        public Builder wal(boolean wal) {
            this.wal = wal;
            return this;
        }

        /**
         * Enables or disables {@code PRAGMA foreign_keys}.
         */
        public Builder foreignKeys(boolean foreignKeys) {
            this.foreignKeys = foreignKeys;
            return this;
        }

        /**
         * Sets {@code PRAGMA busy_timeout}. Zero is allowed; negative values are
         * rejected.
         */
        public Builder busyTimeout(Duration busyTimeout) {
            this.busyTimeout = busyTimeout;
            return this;
        }

        /**
         * Sets {@code PRAGMA synchronous}.
         */
        public Builder synchronous(Synchronous synchronous) {
            this.synchronous = synchronous;
            return this;
        }

        /**
         * Builds and validates the immutable option set.
         */
        public DatabaseOptions build() {
            if (busyTimeout == null || busyTimeout.isNegative()) {
                throw new StorageException("busyTimeout must be zero or positive");
            }
            if (synchronous == null) {
                throw new StorageException("synchronous is required");
            }
            return new DatabaseOptions(this);
        }
    }
}
