package com.jdesk.frame.vfs;

import com.jdesk.frame.storage.DatabaseMigration;
import com.jdesk.frame.storage.DatabaseSession;

import java.util.List;

/**
 * Schema migrations of the managed VFS snapshot tables. The tables belong to the framework but live in the owner's
 * database, so they use their own namespace through {@link com.jdesk.frame.storage.DatabaseHandle#migrate}.
 * If you change a column, check the inserts and {@code FILE_SELECT} in {@link SqliteVfsCatalog}.
 */
final class VfsCatalogSchema {

    /** Schema namespace of the VFS tables in the owner's database. */
    static final String NAMESPACE = "jdesk.vfs";

    private VfsCatalogSchema() {
    }

    /** Returns every migration of this namespace. */
    static List<DatabaseMigration> migrations() {
        return List.of(DatabaseMigration.of(1, "vfs-snapshot-v01", session -> {
            createTables(session);
            createIndexes(session);
        }));
    }

    private static void createTables(DatabaseSession session) {
        // root_key is the stable key that recognizes a root after its folder moves (see VfsCatalog#registerRoot).
        session.update("""
                create table jdesk_vfs_roots (
                  root_id text primary key,
                  root_key text not null,
                  name text not null,
                  root_path text not null,
                  created_at integer not null,
                  updated_at integer not null,
                  unique(root_path)
                )
                """);
        // native_file_id is the second matching clue; empty means unavailable and must never rule out a candidate.
        // read_only is the user's flag (setReadOnly); reconcile, rescan and move updates must not touch it.
        session.update("""
                create table jdesk_vfs_files (
                  file_id text primary key,
                  root_id text not null,
                  relative_path text not null,
                  parent_path text not null,
                  file_name text not null,
                  file_name_sort blob not null,
                  status text not null,
                  directory integer not null,
                  size integer not null,
                  modified_at integer not null,
                  file_created_at integer not null,
                  content_version integer not null,
                  created_at integer not null,
                  updated_at integer not null,
                  native_file_id text not null,
                  read_only integer not null default 0,
                  unique(root_id, relative_path)
                )
                """);
    }

    private static void createIndexes(DatabaseSession session) {
        session.update("""
                create index idx_jdesk_vfs_files_root_path
                on jdesk_vfs_files(root_id, relative_path)
                """);
        session.update("""
                create index idx_jdesk_vfs_files_root_parent
                on jdesk_vfs_files(root_id, parent_path)
                """);
    }
}
