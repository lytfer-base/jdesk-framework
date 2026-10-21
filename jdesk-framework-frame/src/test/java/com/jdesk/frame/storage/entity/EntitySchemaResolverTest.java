package com.jdesk.frame.storage.entity;

import com.jdesk.frame.storage.StorageException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EntitySchemaResolverTest {

    @Test
    void resolvesRecordEntityTableColumnsIdAndIndexes() {
        EntitySchema schema = EntitySchemaResolver.resolve(SampleEntity.class);

        assertEquals("sample_entities", schema.tableName());
        assertEquals("id", schema.idColumn().columnName());
        assertEquals(5, schema.columns().size());
        assertEquals("idx_sample_updated", schema.indexes().get(0).name());
        assertEquals("updated_at", schema.indexes().get(0).columns().get(0));
        assertEquals("text", schema.column("title").sqliteType());
        assertEquals("integer", schema.column("updated_at").sqliteType());
        assertEquals("text", schema.column("body_path").sqliteType());
    }

    @Test
    void rejectsEntityWithoutId() {
        StorageException failure = assertThrows(StorageException.class,
                () -> EntitySchemaResolver.resolve(MissingIdEntity.class));

        assertEquals("Entity requires exactly one @Id column: "
                + MissingIdEntity.class.getName(), failure.getMessage());
    }

    @Table(value = "sample_entities", indexes = {
            @Index(name = "idx_sample_updated", columns = {"updated_at"})
    })
    record SampleEntity(
            @Id @Column("id") String id,
            @Column("title") String title,
            @Column("body_path") Path bodyPath,
            @Column("updated_at") Instant updatedAt,
            @Column("closed") boolean closed,
            @Transient String runtimeOnly
    ) {
    }

    @Table("missing_id")
    record MissingIdEntity(@Column("name") String name) {
    }
}
