-- One migration task owns N data-check tasks. Each check task runs once (full check finishes,
-- incremental check keeps running until stopped/failed) and owns its own result table
-- datacheck_result_<datacheck_tasks.id>, created by the engine when the run starts.
--
-- The covered table pairs are resolved at creation time and live in datacheck_table_tasks: the
-- source table the user picked plus the target table resolved through the migration task's
-- mapping. Runtime never resolves mappings again.
--
-- phase/lag/error are per table because every table runs in its own thread and can finish its
-- full scan, enter incremental, fail or stop independently.

CREATE TABLE datacheck_tasks (
  id                BIGINT        NOT NULL AUTO_INCREMENT,
  public_id         VARCHAR(36)   NOT NULL,
  migration_task_id BIGINT        NOT NULL,
  pipeline_id       BIGINT        NULL,
  name              VARCHAR(128)  NOT NULL,
  mode              VARCHAR(32)   NOT NULL COMMENT 'FULL_ONLY / INCREMENTAL_ONLY / FULL_AND_INCREMENTAL',
  state             VARCHAR(32)   NULL,
  options           JSON          NULL,
  lag_ms            BIGINT        NULL COMMENT 'aggregate over the table rows; informational',
  error_message     VARCHAR(1024) NULL,
  created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_datacheck_public_id (public_id),
  UNIQUE KEY uk_datacheck_task_name (migration_task_id, name),
  KEY idx_datacheck_migration_task (migration_task_id),
  KEY idx_datacheck_state (state),
  CONSTRAINT fk_datacheck_migration_task FOREIGN KEY (migration_task_id)
    REFERENCES migration_tasks (id),
  CONSTRAINT fk_datacheck_pipeline FOREIGN KEY (pipeline_id)
    REFERENCES task_pipelines (id)
) AUTO_INCREMENT = 1000;

CREATE TABLE datacheck_table_tasks (
  datacheck_task_id BIGINT        NOT NULL,
  source_table_id   VARCHAR(128)  NOT NULL COMMENT 'source schema.table',
  target_table_id   VARCHAR(128)  NOT NULL COMMENT 'target schema.table, resolved at creation',
  phase             VARCHAR(16)   NOT NULL COMMENT 'PENDING / FULL / INCREMENTAL',
  status            VARCHAR(16)   NOT NULL COMMENT 'PENDING / RUNNING / COMPLETED / FAILED / STOPPED',
  lag_ms            BIGINT        NULL,
  error_message     VARCHAR(1024) NULL,
  started_at        DATETIME      NULL,
  finished_at       DATETIME      NULL,
  updated_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (datacheck_task_id, source_table_id),
  CONSTRAINT fk_datacheck_table_task FOREIGN KEY (datacheck_task_id)
    REFERENCES datacheck_tasks (id)
);
