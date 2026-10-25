-- H2-compatible copy of the final metadata schema (no prefix indexes / COMMENT / AUTO_INCREMENT start).
-- Mirrors db/migration/V10__task_pipelines.sql: plural tables, `options` columns, task_pipelines.

CREATE TABLE endpoints (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  name        VARCHAR(128) NOT NULL,
  type        VARCHAR(16)  NOT NULL,
  host        VARCHAR(255) NOT NULL,
  port        INT          NOT NULL,
  db_name     VARCHAR(128) NOT NULL,
  username    VARCHAR(128) NOT NULL,
  password    VARCHAR(512) NOT NULL,
  options     JSON         NULL,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_endpoint_name (name)
);

CREATE TABLE migration_tasks (
  id                  BIGINT       NOT NULL AUTO_INCREMENT,
  name                VARCHAR(128) NOT NULL,
  public_id           VARCHAR(36)  NOT NULL,
  mode                VARCHAR(32)  NOT NULL,
  phase               VARCHAR(32)  NOT NULL,
  state               VARCHAR(32)  NULL,
  source_endpoint_id  BIGINT       NOT NULL,
  target_endpoint_id  BIGINT       NOT NULL,
  options             JSON         NOT NULL,
  precheck_results    JSON         NULL,
  lag_ms              BIGINT       NULL,
  error_message       VARCHAR(1024) NULL,
  created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_task_name (name),
  CONSTRAINT uk_task_public_id UNIQUE (public_id),
  KEY idx_task_state (state),
  CONSTRAINT fk_task_source_endpoint FOREIGN KEY (source_endpoint_id) REFERENCES endpoints (id),
  CONSTRAINT fk_task_target_endpoint FOREIGN KEY (target_endpoint_id) REFERENCES endpoints (id)
);

CREATE TABLE task_pipelines (
  id                   BIGINT       NOT NULL AUTO_INCREMENT,
  task_id              BIGINT       NOT NULL,
  source_endpoint_id   BIGINT       NOT NULL,
  target_endpoint_id   BIGINT       NOT NULL,
  capture_options      JSON         NOT NULL,
  replay_options       JSON         NOT NULL,
  created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_pipeline_route (task_id, source_endpoint_id, target_endpoint_id),
  CONSTRAINT fk_pipeline_task    FOREIGN KEY (task_id)            REFERENCES migration_tasks (id),
  CONSTRAINT fk_pipeline_source  FOREIGN KEY (source_endpoint_id) REFERENCES endpoints (id),
  CONSTRAINT fk_pipeline_target  FOREIGN KEY (target_endpoint_id) REFERENCES endpoints (id)
);

CREATE TABLE debezium_offsets (
  id          BIGINT   NOT NULL AUTO_INCREMENT,
  pipeline_id BIGINT   NOT NULL,
  offset_key  VARCHAR(1024) NOT NULL,
  offset_val  TEXT     NULL,
  updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_offset_pipeline_key (pipeline_id, offset_key),
  CONSTRAINT fk_offset_pipeline FOREIGN KEY (pipeline_id) REFERENCES task_pipelines (id)
);

CREATE TABLE debezium_schema_histories (
  id            BIGINT     NOT NULL AUTO_INCREMENT,
  pipeline_id   BIGINT     NOT NULL,
  record_seq    INT        NOT NULL,
  history_data  CLOB       NOT NULL,
  created_at    DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_history_pipeline_seq (pipeline_id, record_seq),
  CONSTRAINT fk_history_pipeline FOREIGN KEY (pipeline_id) REFERENCES task_pipelines (id)
);

CREATE TABLE migration_logs (
  id         BIGINT        NOT NULL AUTO_INCREMENT,
  task_id    BIGINT        NOT NULL,
  logged_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  level      VARCHAR(16)   NOT NULL,
  message    VARCHAR(2000) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_log_task_id (task_id, id),
  CONSTRAINT fk_log_task FOREIGN KEY (task_id) REFERENCES migration_tasks (id)
);

CREATE TABLE task_snapshot_complete_tables (
  pipeline_id BIGINT       NOT NULL,
  table_name  VARCHAR(512) NOT NULL,
  row_count   BIGINT       NULL,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (pipeline_id, table_name),
  CONSTRAINT fk_snapshot_complete_pipeline FOREIGN KEY (pipeline_id) REFERENCES task_pipelines (id)
);
