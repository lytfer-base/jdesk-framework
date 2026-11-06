package com.jdesk.frame.storage;

/**
 * Thrown when a database holds a schema version higher than the program supports for one namespace.
 * The database has not been changed when this is thrown.
 */
public final class DatabaseTooNewException extends StorageException {

    private final String databaseId;
    private final String namespace;
    private final int databaseVersion;
    private final int supportedVersion;

    /**
     * Creates the exception for one namespace of one database.
     */
    public DatabaseTooNewException(String databaseId, String namespace, int databaseVersion, int supportedVersion) {
        super("Database " + databaseId + " is newer than this program: namespace " + namespace + " is at version "
                + databaseVersion + ", supported up to " + supportedVersion);
        this.databaseId = databaseId;
        this.namespace = namespace;
        this.databaseVersion = databaseVersion;
        this.supportedVersion = supportedVersion;
    }

    /**
     * Returns the id of the refused database.
     */
    public String databaseId() {
        return databaseId;
    }

    /**
     * Returns the schema namespace that is too new.
     */
    public String namespace() {
        return namespace;
    }

    /**
     * Returns the version stored in the database.
     */
    public int databaseVersion() {
        return databaseVersion;
    }

    /**
     * Returns the highest version this program supports for the namespace.
     */
    public int supportedVersion() {
        return supportedVersion;
    }
}
