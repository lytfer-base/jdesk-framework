package com.jdesk.frame.storage.dao;

import com.jdesk.core.app.PathManager;
import com.jdesk.core.disposer.Disposer;
import com.jdesk.frame.storage.DatabaseDescriptor;
import com.jdesk.frame.storage.DatabaseHandle;
import com.jdesk.frame.storage.DatabaseOwner;
import com.jdesk.frame.storage.sqlite.SqliteDatabaseService;
import com.jdesk.frame.storage.entity.Column;
import com.jdesk.frame.storage.entity.Id;
import com.jdesk.frame.storage.entity.Index;
import com.jdesk.frame.storage.entity.Table;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlEntityDaoTest {

    @TempDir
    Path homeDir;

    @AfterEach
    void clearState() {
        Disposer.clearAll();
        PathManager.resetForTests();
    }

    @Test
    void generatedEntityDaoSupportsCrudAndTypeConversion() {
        PathManager.setHomePathForTests(homeDir);
        DatabaseHandle database = new SqliteDatabaseService().open(DatabaseDescriptor.builder("dao.records")
                .owner(DatabaseOwner.application("tests"))
                .name("dao-records")
                .entity(RecordEntity.class)
                .build());

        try {
            EntityDao<RecordEntity, String> dao = database.entityDao(RecordEntity.class, String.class);
            RecordEntity entity = new RecordEntity("r1", "Title", homeDir.resolve("r1.md"),
                    Instant.ofEpochMilli(1_700_000_000_000L), true, RecordKind.NOTE);

            dao.insert(entity);

            Optional<RecordEntity> loaded = dao.findById("r1");
            assertTrue(loaded.isPresent());
            assertEquals(entity, loaded.get());

            dao.update(new RecordEntity("r1", "Updated", homeDir.resolve("r1-updated.md"),
                    Instant.ofEpochMilli(1_700_000_001_000L), false, RecordKind.TASK));

            RecordEntity updated = dao.findById("r1").orElseThrow();
            assertEquals("Updated", updated.title());
            assertEquals(false, updated.closed());
            assertEquals(RecordKind.TASK, updated.kind());

            dao.deleteById("r1");

            assertFalse(dao.findById("r1").isPresent());
        } finally {
            Disposer.dispose(database);
        }
    }

    enum RecordKind {
        NOTE, TASK
    }

    @Table(value = "records", indexes = {
            @Index(name = "idx_records_updated", columns = {"updated_at"})
    })
    record RecordEntity(
            @Id @Column("id") String id,
            @Column("title") String title,
            @Column("body_path") Path bodyPath,
            @Column("updated_at") Instant updatedAt,
            @Column("closed") boolean closed,
            @Column("kind") RecordKind kind
    ) {
    }
}
