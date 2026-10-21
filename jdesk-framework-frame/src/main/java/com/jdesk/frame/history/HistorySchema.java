package com.jdesk.frame.history;

import com.jdesk.frame.storage.DatabaseMigration;
import com.jdesk.frame.storage.DatabaseSession;
import java.util.List;

/**
 * Schema migrations of the local history database {@code history.sqlite}: object index, activities, version log and
 * heads. If you change a column, check the inserts and selects in {@link SqliteLocalHistoryStore}.
 */
final class HistorySchema {

    private HistorySchema() {
    }

    static List<DatabaseMigration> migrations() {
        return List.of(DatabaseMigration.of(1, "history-v01", HistorySchema::createV01));
    }

    private static void createV01(DatabaseSession session) {
        session.update("""
                create table lh_objects (
                  hash text primary key,
                  size integer not null,
                  stored_size integer not null,
                  created_at integer not null
                )
                """);
        session.update("""
                create table lh_activities (
                  activity_id integer primary key autoincrement,
                  name text not null,
                  source text not null,
                  started_at integer not null,
                  ended_at integer,
                  params text
                )
                """);
        session.update("""
                create table lh_versions (
                  version_id integer primary key autoincrement,
                  key_kind text not null,
                  key_id text not null,
                  path text not null,
                  object_hash text,
                  norm_hash text,
                  size integer not null,
                  captured_at integer not null,
                  source text not null,
                  activity_id integer,
                  label text,
                  sealed integer not null default 0,
                  skipped_reason text
                )
                """);
        session.update("create index idx_lh_versions_key on lh_versions(key_kind, key_id, captured_at)");
        session.update("create index idx_lh_versions_object on lh_versions(object_hash)");
        session.update("create index idx_lh_versions_activity on lh_versions(activity_id)");
        session.update("""
                create table lh_heads (
                  key_kind text not null,
                  key_id text not null,
                  path text not null,
                  head_version_id integer not null,
                  content_hash text,
                  deleted_at integer,
                  primary key (key_kind, key_id)
                )
                """);
    }
}
