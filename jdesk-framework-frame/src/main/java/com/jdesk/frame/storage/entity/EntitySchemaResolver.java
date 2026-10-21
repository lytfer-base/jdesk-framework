package com.jdesk.frame.storage.entity;

import com.jdesk.frame.storage.StorageException;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Resolves lightweight entity annotations into cached schema metadata.
 *
 * <p>The first version intentionally supports Java records only. That keeps
 * constructor mapping deterministic and avoids the complexity of mutable object
 * hydration before the storage framework has more real-world usage.
 */
public final class EntitySchemaResolver {

    private static final ConcurrentMap<Class<?>, EntitySchema> CACHE = new ConcurrentHashMap<>();

    private EntitySchemaResolver() {
    }

    /**
     * Resolves and caches schema metadata for one annotated entity class.
     */
    public static EntitySchema resolve(Class<?> entityClass) {
        return CACHE.computeIfAbsent(entityClass, EntitySchemaResolver::resolveUncached);
    }

    /**
     * Parses annotations and validates the supported entity shape.
     */
    private static EntitySchema resolveUncached(Class<?> entityClass) {
        Table table = entityClass.getAnnotation(Table.class);
        if (table == null) {
            throw new StorageException("Entity requires @Table: " + entityClass.getName());
        }
        if (!entityClass.isRecord()) {
            throw new StorageException("Only Java record entities are supported: " + entityClass.getName());
        }

        List<EntitySchema.ColumnSchema> columns = new ArrayList<>();
        EntitySchema.ColumnSchema idColumn = null;
        for (RecordComponent component : entityClass.getRecordComponents()) {
            if (component.isAnnotationPresent(Transient.class)) {
                continue;
            }
            Column column = component.getAnnotation(Column.class);
            if (column == null) {
                throw new StorageException("Record component requires @Column: "
                        + entityClass.getName() + "." + component.getName());
            }
            boolean id = component.isAnnotationPresent(Id.class);
            EntitySchema.ColumnSchema schema = new EntitySchema.ColumnSchema(
                    component.getName(),
                    column.value(),
                    component.getType(),
                    sqliteType(component.getType()),
                    id,
                    component.getAccessor());
            if (id) {
                if (idColumn != null) {
                    throw new StorageException("Entity requires exactly one @Id column: " + entityClass.getName());
                }
                idColumn = schema;
            }
            columns.add(schema);
        }
        if (idColumn == null) {
            throw new StorageException("Entity requires exactly one @Id column: " + entityClass.getName());
        }

        List<EntitySchema.IndexSchema> indexes = Arrays.stream(table.indexes())
                .map(index -> new EntitySchema.IndexSchema(index.name(), List.of(index.columns())))
                .toList();
        Constructor<?> constructor = canonicalConstructor(entityClass);
        return new EntitySchema(entityClass, table.value(), columns, idColumn, indexes, constructor);
    }

    /**
     * Locates the canonical record constructor used for row mapping.
     */
    private static Constructor<?> canonicalConstructor(Class<?> entityClass) {
        Class<?>[] parameterTypes = Arrays.stream(entityClass.getRecordComponents())
                .map(RecordComponent::getType)
                .toArray(Class<?>[]::new);
        try {
            return entityClass.getDeclaredConstructor(parameterTypes);
        } catch (NoSuchMethodException e) {
            throw new StorageException("Record canonical constructor not found: " + entityClass.getName(), e);
        }
    }

    /**
     * Maps supported Java property types to SQLite storage classes.
     */
    private static String sqliteType(Class<?> javaType) {
        if (javaType == String.class || javaType == Path.class || javaType.isEnum()) {
            return "text";
        }
        if (javaType == boolean.class || javaType == Boolean.class
                || javaType == int.class || javaType == Integer.class
                || javaType == long.class || javaType == Long.class
                || javaType == Instant.class || javaType == LocalDate.class || javaType == LocalDateTime.class) {
            return "integer";
        }
        if (javaType == double.class || javaType == Double.class
                || javaType == float.class || javaType == Float.class) {
            return "real";
        }
        if (javaType == byte[].class) {
            return "blob";
        }
        throw new StorageException("Unsupported entity column type: " + javaType.getName());
    }
}
