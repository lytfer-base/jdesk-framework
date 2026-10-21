package com.jdesk.frame.storage;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Identifies the module or plugin that owns a database.
 *
 * <p>The owner is part of the managed database path. It keeps application and
 * plugin data separated while still allowing the frame service to release all
 * open databases on application shutdown.
 */
public final class DatabaseOwner {

    private static final Pattern SAFE_PART = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private final String type;
    private final String id;

    private DatabaseOwner(String type, String id) {
        this.type = requireSafe("owner type", type);
        this.id = requireSafe("owner id", id);
    }

    /**
     * Creates an owner for application/module data.
     */
    public static DatabaseOwner application(String appId) {
        return new DatabaseOwner("app", appId);
    }

    /**
     * Creates an owner for plugin-owned data.
     */
    public static DatabaseOwner plugin(String pluginId) {
        return new DatabaseOwner("plugin", pluginId);
    }

    /**
     * Returns the owner category, currently {@code app} or {@code plugin}.
     */
    public String type() {
        return type;
    }

    /**
     * Returns the safe owner identifier within the owner category.
     */
    public String id() {
        return id;
    }

    /**
     * Returns the relative path segment used below the managed database root.
     */
    public String pathSegment() {
        return type + "/" + id;
    }

    private static String requireSafe(String label, String value) {
        String cleaned = Objects.requireNonNull(value, label).trim();
        if (!SAFE_PART.matcher(cleaned).matches()) {
            throw new StorageException("Invalid " + label + ": " + value);
        }
        return cleaned;
    }
}
