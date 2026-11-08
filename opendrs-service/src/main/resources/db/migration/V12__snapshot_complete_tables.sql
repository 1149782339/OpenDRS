-- Replace the (task, direction, status) checkpoint table with a presence=done design keyed by
-- pipeline: one row per table whose full load committed on the target. The pipeline row is
-- created once per physical direction at task creation and never re-created, so it identifies
-- a direction as well as task_id+direction did, without the redundant column.
--
-- No data migration: the resume feature has not shipped; the old table is simply dropped.
DROP TABLE full_load_checkpoints;

CREATE TABLE task_snapshot_complete_tables (
  pipeline_id BIGINT       NOT NULL,
  table_name  VARCHAR(512) NOT NULL COMMENT 'source db.table as Debezium resolves it',
  row_count   BIGINT       NULL COMMENT 'rows Debezium scanned, informational only',
  created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (pipeline_id, table_name),
  CONSTRAINT fk_snapshot_complete_pipeline FOREIGN KEY (pipeline_id) REFERENCES task_pipelines (id)
);
