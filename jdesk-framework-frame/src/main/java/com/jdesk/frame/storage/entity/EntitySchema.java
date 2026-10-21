package com.jdesk.frame.storage.entity;

import com.jdesk.frame.storage.StorageException;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Parsed metadata for a lightweight storage entity.
 *
 * <p>The schema is resolved once per entity class and cached by
 * {@link EntitySchemaResolver}. It contains the information needed to generate
 * table/index SQL, read entity component values and reconstruct record
 * instances from database rows.
 */
public final class EntitySchema {

    private final Class<?> entityClass;
    private final String tableName;
    private final List<ColumnSchema> columns;
    private final ColumnSchema idColumn;
    private final List<IndexSchema> indexes;
    private final Constructor<?> constructor;

    EntitySchema(Class<?> entityClass,
                 String tableName,
                 List<ColumnSchema> columns,
                 ColumnSchema idColumn,
                 List<IndexSchema> indexes,
                 Constructor<?> constructor) {
        this.entityClass = entityClass;
        this.tableName = tableName;
        this.columns = List.copyOf(columns);
        this.idColumn = idColumn;
        this.indexes = List.copyOf(indexes);
        this.constructor = constructor;
        this.constructor.setAccessible(true);
        this.columns.forEach(column -> column.accessor().setAccessible(true));
    }

    /**
     * Returns the Java entity class represented by this schema.
     */
    public Class<?> entityClass() {
        return entityClass;
    }

    /**
     * Returns the SQLite table name from {@link Table#value()}.
     */
    public String tableName() {
        return tableName;
    }

    /**
     * Returns mapped columns in record-constructor order.
     */
    public List<ColumnSchema> columns() {
        return columns;
    }

    /**
     * Returns the single id column.
     */
    public ColumnSchema idColumn() {
        return idColumn;
    }

    /**
     * Returns indexes declared on the entity table.
     */
    public List<IndexSchema> indexes() {
        return indexes;
    }

    /**
     * Finds a mapped column by SQLite column name.
     */
    public ColumnSchema column(String name) {
        return columns.stream()
                .filter(column -> column.columnName().equals(name))
                .findFirst()
                .orElseThrow(() -> new StorageException("Unknown entity column: " + name));
    }

    /**
     * Creates a new record instance using the canonical constructor.
     */
    public Object newInstance(Object[] values) {
        try {
            return constructor.newInstance(values);
        } catch (ReflectiveOperationException e) {
            throw new StorageException("Failed to create entity: " + entityClass.getName(), e);
        }
    }

    public record ColumnSchema(String componentName,
                               String columnName,
                               Class<?> javaType,
                               String sqliteType,
                               boolean id,
                               Method accessor) {
        /**
         * Reads this column's Java value from an entity instance.
         */
        public Object read(Object entity) {
            try {
                return accessor.invoke(entity);
            } catch (ReflectiveOperationException e) {
                throw new StorageException("Failed to read entity property: " + componentName, e);
            }
        }
    }

    public record IndexSchema(String name, List<String> columns) {
    }
}
