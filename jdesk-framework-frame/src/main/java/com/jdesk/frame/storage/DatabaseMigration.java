package com.jdesk.frame.storage;

import java.util.Objects;

/**
 * One schema migration step.
 *
 * <p>Migrations are explicit, versioned changes for schema work that is not
 * safe to infer from annotations. The SQLite runner executes pending
 * migrations in ascending version order and records each result in the schema
 * migration log table.
 */
public interface DatabaseMigration {

    /**
     * Returns the monotonically increasing schema version reached by this step.
     */
    int version();

    /**
     * Returns a human-readable, stable migration name used in migration logs.
     */
    String name();

    /**
     * Applies the migration using the supplied session.
     */
    void migrate(DatabaseSession session);

    /**
     * Convenience factory for simple inline migration definitions.
     */
    static DatabaseMigration of(int version, String name, MigrationBody body) {
        Objects.requireNonNull(body, "body");
        return new DatabaseMigration() {
            @Override
            public int version() {
                return version;
            }

            @Override
            public String name() {
                return name;
            }

            @Override
            public void migrate(DatabaseSession session) {
                body.migrate(session);
            }
        };
    }

    /**
     * Functional body used by {@link #of(int, String, MigrationBody)}.
     */
    @FunctionalInterface
    interface MigrationBody {
        /**
         * Applies migration SQL through a managed session.
         */
        void migrate(DatabaseSession session);
    }
}
