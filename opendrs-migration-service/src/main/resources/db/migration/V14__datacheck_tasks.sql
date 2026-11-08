-- One migration task owns N data-check tasks. Each check task runs once (full check finishes,
-- incremental check keeps running until stopped/failed) and owns its own result table
-- datacheck_result_<datacheck_tasks.id>, created by the engine when the run starts.
--
-- source_tables is the explicit list the check covers: [{"schema":"hr","table":"emp"}]. Target
-- tables are resolved from the migration task's mapping, so they are not stored here.
-- pipeline_id names the physical direction for bidirectional tasks; null means the forward one.

CREATE TABLE datacheck_tasks (
  id                BIGINT        NOT NULL AUTO_INCREMENT,
  public_id         VARCHAR(36)   NOT NULL,
  migration_task_id BIGINT        NOT NULL,
  pipeline_id       BIGINT        NULL,
  name              VARCHAR(128)  NOT NULL,
  mode              VARCHAR(32)   NOT NULL COMMENT 'FULL_ONLY / INCREMENTAL_ONLY / FULL_AND_INCREMENTAL',
  phase             VARCHAR(32)   NOT NULL COMMENT 'CREATED / FULL / INCREMENTAL',
  state             VARCHAR(32)   NULL,
  source_tables     JSON          NOT NULL COMMENT 'explicit source tables: [{"schema":"hr","table":"emp"}]',
  options           JSON          NULL,
  lag_ms            BIGINT        NULL,
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
