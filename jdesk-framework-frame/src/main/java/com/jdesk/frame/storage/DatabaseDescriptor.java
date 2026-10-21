package com.jdesk.frame.storage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Describes one database resource owned by an application module or plugin.
 *
 * <p>The descriptor is immutable after build. It does not open the database by
 * itself; {@link DatabaseService#open(DatabaseDescriptor)} uses it to resolve
 * the file path, apply options, run migrations and prepare entity metadata.
 */
public final class DatabaseDescriptor {

    private static final Pattern DATABASE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private final String id;
    private final DatabaseOwner owner;
    private final String name;
    private final DatabaseOptions options;
    private final List<DatabaseMigration> migrations;
    private final List<Class<?>> entities;
    private final Path location;

    private DatabaseDescriptor(Builder builder) {
        this.id = validateId(builder.id);
        this.owner = Objects.requireNonNull(builder.owner, "owner");
        this.name = validateName(builder.name == null ? builder.id : builder.name);
        this.options = builder.options == null ? DatabaseOptions.defaults() : builder.options;
        this.migrations = List.copyOf(builder.migrations);
        this.entities = List.copyOf(builder.entities);
        this.location = validateLocation(builder.location);
    }

    /**
     * Starts a descriptor for a stable database id.
     *
     * <p>The id is used for service tracking and migration namespace decisions,
     * so it should remain stable across application releases.
     */
    public static Builder builder(String id) {
        return new Builder(id);
    }

    /**
     * Returns the stable id used by {@link DatabaseService}.
     */
    public String id() {
        return id;
    }

    /**
     * Returns the application or plugin that owns this database file.
     */
    public DatabaseOwner owner() {
        return owner;
    }

    /**
     * Returns the file-name-safe database name without the {@code .sqlite}
     * suffix.
     */
    public String name() {
        return name;
    }

    /**
     * Returns SQLite runtime options for this database.
     */
    public DatabaseOptions options() {
        return options;
    }

    /**
     * Returns explicit schema migrations in declaration order.
     *
     * <p>The SQLite runner sorts them by {@link DatabaseMigration#version()}
     * before execution.
     */
    public List<DatabaseMigration> migrations() {
        return migrations;
    }

    /**
     * Returns entity classes that should have their table/index schema prepared
     * when the database opens.
     */
    public List<Class<?>> entities() {
        return entities;
    }

    /**
     * Returns an explicit directory for the database file, or {@code null} to
     * use the default system-managed location under {@code system/databases}.
     */
    public Path location() {
        return location;
    }

    private static String validateId(String value) {
        String cleaned = Objects.requireNonNull(value, "id").trim();
        if (!DATABASE_ID.matcher(cleaned).matches()) {
            throw new StorageException("Invalid database id: " + value);
        }
        return cleaned;
    }

    private static String validateName(String value) {
        String cleaned = Objects.requireNonNull(value, "name").trim();
        if (!DATABASE_ID.matcher(cleaned).matches()) {
            throw new StorageException("Invalid database name: " + value);
        }
        return cleaned;
    }

    private static Path validateLocation(Path location) {
        if (location != null && !location.isAbsolute()) {
            throw new StorageException("Database location must be an absolute path: " + location);
        }
        return location;
    }

    public static final class Builder {
        private final String id;
        private DatabaseOwner owner;
        private String name;
        private DatabaseOptions options;
        private final List<DatabaseMigration> migrations = new ArrayList<>();
        private final List<Class<?>> entities = new ArrayList<>();
        private Path location;

        private Builder(String id) {
            this.id = id;
        }

        /**
         * Sets the application or plugin that owns the database file.
         */
        public Builder owner(DatabaseOwner owner) {
            this.owner = owner;
            return this;
        }

        /**
         * Sets the file-name-safe database name. When omitted, the descriptor id
         * is used as the name.
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Overrides SQLite runtime options. Defaults are used when omitted.
         */
        public Builder options(DatabaseOptions options) {
            this.options = options;
            return this;
        }

        /**
         * Registers one explicit migration step.
         */
        public Builder migration(DatabaseMigration migration) {
            this.migrations.add(Objects.requireNonNull(migration, "migration"));
            return this;
        }

        /**
         * Registers an annotated entity whose table/index schema should be
         * prepared during database initialization.
         */
        public Builder entity(Class<?> entityClass) {
            this.entities.add(Objects.requireNonNull(entityClass, "entityClass"));
            return this;
        }

        /**
         * Places the database file in an explicit absolute directory instead of
         * the default system-managed location. Used for library-local metadata
         * databases that travel with their content.
         */
        public Builder location(Path location) {
            this.location = location;
            return this;
        }

        /**
         * Builds and validates the immutable descriptor.
         */
        public DatabaseDescriptor build() {
            return new DatabaseDescriptor(this);
        }
    }
}
