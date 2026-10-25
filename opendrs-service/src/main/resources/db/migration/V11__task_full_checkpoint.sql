-- Per-table full-load checkpoints for resume-by-table, one row per (task, direction, table).
--
-- The engine reports two strictly ordered events per full load (see FullLoadCheckpointListener):
-- DATA_COLLECTIONS_RESOLVED once with the snapshot's table list, then TABLE_SCAN_COMPLETED per
-- table after all of that table's rows are committed on the target. Resolved lands as pending
-- rows (INSERT IGNORE: a resume run re-resolves only the remaining tables and must never reset
-- the rows written before the crash); scan-completed upserts the row to done with the scanned
-- row count.
--
-- On restart the coordinator reads the done set per (task, direction): done tables are skipped,
-- the rest are re-snapshotted together with the half-finished ones (snapshot.include.collection
-- .list via the ResumableInitialSnapshotter) while the P0 binlog position is reused as stored.
-- direction is the engine's direction tag (forward / reverse); a bidirectional task checkpoints
-- its two pipelines independently.

CREATE TABLE full_load_checkpoints (
  task_id     BIGINT       NOT NULL,
  direction   VARCHAR(16)  NOT NULL,
  table_name  VARCHAR(512) NOT NULL COMMENT 'source db.table as Debezium resolves it',
  status      VARCHAR(16)  NOT NULL COMMENT 'pending / loading / done; today only pending and done are written',
  row_count   BIGINT       NULL COMMENT 'rows Debezium scanned for a done table, informational only',
  updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (task_id, direction, table_name),
  CONSTRAINT fk_checkpoint_task FOREIGN KEY (task_id) REFERENCES migration_tasks (id)
);
