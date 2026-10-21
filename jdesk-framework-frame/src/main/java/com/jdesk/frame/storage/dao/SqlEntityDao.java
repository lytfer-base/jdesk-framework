package com.jdesk.frame.storage.dao;

import com.jdesk.frame.storage.DatabaseHandle;
import com.jdesk.frame.storage.DatabaseRow;
import com.jdesk.frame.storage.StorageException;
import com.jdesk.frame.storage.entity.EntitySchema;
import com.jdesk.frame.storage.entity.EntitySchemaResolver;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * SQLite-oriented generated CRUD DAO backed by the generic DatabaseHandle API.
 *
 * <p>This class is the first concrete DAO implementation for annotated Java
 * record entities. It creates missing tables/indexes, generates simple CRUD SQL
 * and delegates all execution to {@link DatabaseHandle} so lifecycle,
 * threading and backend exceptions stay centralized.
 */
public final class SqlEntityDao<T, ID> implements EntityDao<T, ID> {

    private final DatabaseHandle database;
    private final EntitySchema schema;
    private final Class<ID> idClass;

    /**
     * Creates a DAO for one annotated entity class and prepares its table/index
     * schema if needed.
     */
    public SqlEntityDao(DatabaseHandle database, Class<T> entityClass, Class<ID> idClass) {
        this.database = database;
        this.schema = EntitySchemaResolver.resolve(entityClass);
        this.idClass = idClass;
        ensureSchema();
    }

    /**
     * Reads one row by the entity id column.
     */
    @Override
    public Optional<T> findById(ID id) {
        return database.read(session -> session.queryOne(
                "select * from " + schema.tableName() + " where " + schema.idColumn().columnName() + " = ?",
                this::map,
                toDatabaseValue(id)));
    }

    /**
     * Inserts all mapped columns. The id value is expected to already be present
     * on the entity instance.
     */
    @Override
    public ID insert(T entity) {
        List<EntitySchema.ColumnSchema> columns = schema.columns();
        StringJoiner columnNames = new StringJoiner(", ");
        StringJoiner placeholders = new StringJoiner(", ");
        List<Object> params = new ArrayList<>();
        for (EntitySchema.ColumnSchema column : columns) {
            columnNames.add(column.columnName());
            placeholders.add("?");
            params.add(toDatabaseValue(column.read(entity)));
        }
        database.write(session -> {
            session.update("insert into " + schema.tableName()
                    + "(" + columnNames + ") values (" + placeholders + ")",
                    params.toArray());
            return null;
        });
        return idClass.cast(schema.idColumn().read(entity));
    }

    /**
     * Updates all mapped non-id columns for the entity id.
     */
    @Override
    public void update(T entity) {
        StringJoiner assignments = new StringJoiner(", ");
        List<Object> params = new ArrayList<>();
        for (EntitySchema.ColumnSchema column : schema.columns()) {
            if (column.id()) {
                continue;
            }
            assignments.add(column.columnName() + " = ?");
            params.add(toDatabaseValue(column.read(entity)));
        }
        params.add(toDatabaseValue(schema.idColumn().read(entity)));
        database.write(session -> {
            session.update("update " + schema.tableName() + " set " + assignments
                    + " where " + schema.idColumn().columnName() + " = ?",
                    params.toArray());
            return null;
        });
    }

    /**
     * Deletes a row using the entity id column.
     */
    @Override
    public void deleteById(ID id) {
        database.write(session -> {
            session.update("delete from " + schema.tableName()
                    + " where " + schema.idColumn().columnName() + " = ?",
                    toDatabaseValue(id));
            return null;
        });
    }

    /**
     * Creates the entity table and indexes when they do not already exist.
     *
     * <p>The first implementation intentionally avoids destructive schema
     * changes. Existing-table evolution such as adding missing columns is left
     * to explicit migrations until a safe schema-diff policy is added.
     */
    public void ensureSchema() {
        database.write(session -> {
            session.update(createTableSql());
            for (EntitySchema.IndexSchema index : schema.indexes()) {
                session.update(createIndexSql(index));
            }
            return null;
        });
    }

    @SuppressWarnings("unchecked")
    /**
     * Converts one database row into a record instance.
     */
    private T map(DatabaseRow row) {
        Object[] values = new Object[schema.columns().size()];
        for (int i = 0; i < schema.columns().size(); i++) {
            EntitySchema.ColumnSchema column = schema.columns().get(i);
            values[i] = fromDatabaseValue(row, column);
        }
        return (T) schema.newInstance(values);
    }

    /**
     * Builds first-version non-destructive CREATE TABLE SQL.
     */
    private String createTableSql() {
        StringJoiner definitions = new StringJoiner(", ");
        for (EntitySchema.ColumnSchema column : schema.columns()) {
            String definition = column.columnName() + " " + column.sqliteType();
            if (column.id()) {
                definition += " primary key";
            }
            definitions.add(definition);
        }
        return "create table if not exists " + schema.tableName() + " (" + definitions + ")";
    }

    /**
     * Builds non-destructive CREATE INDEX SQL for one declared index.
     */
    private String createIndexSql(EntitySchema.IndexSchema index) {
        return "create index if not exists " + index.name() + " on "
                + schema.tableName() + "(" + String.join(", ", index.columns()) + ")";
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    /**
     * Converts one row column to the Java component type.
     */
    private Object fromDatabaseValue(DatabaseRow row, EntitySchema.ColumnSchema column) {
        Class<?> type = column.javaType();
        if (type == String.class) {
            return row.getString(column.columnName());
        }
        if (type == Path.class) {
            return row.getPath(column.columnName());
        }
        if (type == boolean.class || type == Boolean.class) {
            return row.getBoolean(column.columnName());
        }
        if (type == int.class || type == Integer.class) {
            return row.getInt(column.columnName());
        }
        if (type == long.class || type == Long.class) {
            return row.getLong(column.columnName());
        }
        if (type == Instant.class) {
            return row.getInstant(column.columnName());
        }
        if (type == LocalDateTime.class) {
            return row.getLocalDateTime(column.columnName());
        }
        if (type == LocalDate.class) {
            return Instant.ofEpochMilli(row.getLong(column.columnName())).atZone(ZoneOffset.UTC).toLocalDate();
        }
        if (type.isEnum()) {
            String name = row.getString(column.columnName());
            return name == null ? null : Enum.valueOf((Class<? extends Enum>) type.asSubclass(Enum.class), name);
        }
        throw new StorageException("Unsupported database value type: " + type.getName());
    }

    /**
     * Converts a Java value to a SQLite-storable parameter value.
     */
    private Object toDatabaseValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool ? 1 : 0;
        }
        if (value instanceof Path path) {
            return path.toString();
        }
        if (value instanceof Instant instant) {
            return instant.toEpochMilli();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.toInstant(ZoneOffset.UTC).toEpochMilli();
        }
        if (value instanceof LocalDate localDate) {
            return localDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        return value;
    }
}
