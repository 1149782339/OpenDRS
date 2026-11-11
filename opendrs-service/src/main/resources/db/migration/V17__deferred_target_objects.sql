-- Deferred target objects: one-way full + incremental tasks load into PK-only target tables.
-- Before the full load the task reads the secondary indexes, unique keys and foreign keys of
-- its target tables back from the target catalog, records them here, then drops them; plain
-- indexes come back right after the snapshot, unique and foreign keys once replay has passed
-- the snapshot end position (snapshot_end_position).
--
-- deferred_gate: NONE (task predates the feature or does not use it) -> CAPTURED (objects
-- recorded, the capture never runs again) -> RESTORED (every object rebuilt).
ALTER TABLE task_pipelines
  ADD COLUMN deferred_gate VARCHAR(16) NOT NULL DEFAULT 'NONE' AFTER replay_options,
  ADD COLUMN snapshot_end_position JSON NULL AFTER deferred_gate;

CREATE TABLE task_deferred_objects (
  id           BIGINT        NOT NULL AUTO_INCREMENT,
  pipeline_id  BIGINT        NOT NULL,
  schema_name  VARCHAR(128)  NOT NULL COMMENT 'target schema',
  table_name   VARCHAR(128)  NOT NULL COMMENT 'target table',
  object_type  VARCHAR(16)   NOT NULL COMMENT 'INDEX / UNIQUE / FOREIGN_KEY',
  object_name  VARCHAR(128)  NOT NULL COMMENT 'identifiers are at most 64 characters on both targets',
  create_ddl   TEXT          NOT NULL,
  drop_ddl     TEXT          NOT NULL,
  post_ddl     TEXT          NULL COMMENT 'follow-up after the create, e.g. VALIDATE CONSTRAINT',
  build_order  INT           NOT NULL DEFAULT 0,
  state        VARCHAR(16)   NOT NULL COMMENT 'CAPTURED / DROPPED / REBUILT / FAILED',
  attempts     INT           NOT NULL DEFAULT 0,
  last_error   VARCHAR(1024) NULL,
  created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_deferred_object (pipeline_id, schema_name, table_name, object_type, object_name),
  CONSTRAINT fk_deferred_pipeline FOREIGN KEY (pipeline_id) REFERENCES task_pipelines (id)
);
