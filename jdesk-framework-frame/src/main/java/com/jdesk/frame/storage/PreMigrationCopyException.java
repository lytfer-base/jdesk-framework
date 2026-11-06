package com.jdesk.frame.storage;

import java.nio.file.Path;

/**
 * Thrown when the copy of a database that must be left before its pending migrations can't be left.
 * No migration has run for the refused open or migrate call.
 */
public final class PreMigrationCopyException extends StorageException {

    private final String databaseId;
    private final Path copyDir;

    /**
     * Creates the exception for one database and the folder its copy should have gone to.
     */
    public PreMigrationCopyException(String databaseId, Path copyDir, Throwable cause) {
        super("Failed to leave a copy of database " + databaseId + " before migrating it: " + copyDir, cause);
        this.databaseId = databaseId;
        this.copyDir = copyDir;
    }

    /**
     * Returns the id of the refused database.
     */
    public String databaseId() {
        return databaseId;
    }

    /**
     * Returns the folder the copy should have gone to.
     */
    public Path copyDir() {
        return copyDir;
    }
}
