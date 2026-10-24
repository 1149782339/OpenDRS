-- Physical pipelines + final schema shape, in one dev-stage rebuild.
--
-- The schema moves to its final form: every table plural (endpoints, migration_tasks,
-- task_pipelines, debezium_offsets, debezium_schema_histories, migration_logs), every JSON
-- config column named `options` (or `<domain>_options` when the row carries more than one domain).
--
-- A task is the user-facing unit (what to move, with what options); a pipeline is one physical
-- source -> target link with its own Debezium engine, offset rows and schema history. One-way
-- tasks own one pipeline row, bidirectional tasks own two (endpoints swapped). Future topologies
-- (fan-in / fan-out) are more rows on this same table — the schema has no topology concept.
--
-- capture_options / replay_options hold the direction-specific config the runtime cannot derive:
-- today only the synthetic full-load GTID namespace each direction stamps on its target
-- (bidirectional loop prevention). The drop set is derived at runtime from the sibling pipeline's
-- namespace plus the target endpoint's server_uuid.
--
-- No data is converted: all tables are dropped and recreated empty (dev-stage decision, same as
-- V7/V8). Tasks 1023/1025 and the demo sources must be recreated after this migration.

DROP TABLE IF EXISTS migration_log;
DROP TABLE IF EXISTS debezium_offset;
DROP TABLE IF EXISTS debezium_schema_history;
DROP TABLE IF EXISTS task_pipelines;
DROP TABLE IF EXISTS migration_task;
DROP TABLE IF EXISTS connection_info;

CREATE TABLE endpoints (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  name        VARCHAR(128) NOT NULL,
  type        VARCHAR(16)  NOT NULL COMMENT 'MYSQL / POSTGRESQL',
  host        VARCHAR(255) NOT NULL,
  port        INT          NOT NULL,
  db_name     VARCHAR(128) NOT NULL,
  username    VARCHAR(128) NOT NULL,
  password    VARCHAR(512) NOT NULL,
  options     JSON         NULL,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_endpoint_name (name)
);

CREATE TABLE migration_tasks (
  id                  BIGINT        NOT NULL AUTO_INCREMENT,
  name                VARCHAR(128)  NOT NULL,
  public_id           VARCHAR(36)   NOT NULL,
  mode                VARCHAR(32)   NOT NULL,
  phase               VARCHAR(32)   NOT NULL,
  state               VARCHAR(32)   NULL,
  source_endpoint_id  BIGINT        NOT NULL,
  target_endpoint_id  BIGINT        NOT NULL,
  options             JSON          NOT NULL,
  precheck_results    JSON          NULL,
  lag_ms              BIGINT        NULL,
  error_message       VARCHAR(1024) NULL,
  created_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_task_name (name),
  UNIQUE KEY uk_task_public_id (public_id),
  KEY idx_task_state (state),
  CONSTRAINT fk_task_source_endpoint FOREIGN KEY (source_endpoint_id) REFERENCES endpoints (id),
  CONSTRAINT fk_task_target_endpoint FOREIGN KEY (target_endpoint_id) REFERENCES endpoints (id)
) AUTO_INCREMENT = 1000;

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
  offset_key  TEXT     NOT NULL,
  offset_val  TEXT     NULL,
  updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_offset_pipeline_key (pipeline_id, offset_key(255)),
  CONSTRAINT fk_offset_pipeline FOREIGN KEY (pipeline_id) REFERENCES task_pipelines (id)
);

CREATE TABLE debezium_schema_histories (
  id            BIGINT     NOT NULL AUTO_INCREMENT,
  pipeline_id   BIGINT     NOT NULL,
  record_seq    INT        NOT NULL,
  history_data  MEDIUMTEXT NOT NULL,
  created_at    DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_history_pipeline_seq (pipeline_id, record_seq),
  CONSTRAINT fk_history_pipeline FOREIGN KEY (pipeline_id) REFERENCES task_pipelines (id)
);

-- Task log shown on the detail page. Written from two sides: the service records lifecycle events
-- it owns (start, phase change, stop, failure, precheck summary) and buffers what the engine
-- reports through MigrationLogListener.
--
-- level is INFO / WARNING / ERROR. It is a VARCHAR rather than an enum so adding a level later does
-- not need a schema change; the service validates the value it writes.
--
-- No ON DELETE CASCADE: MigrationTaskService.delete() removes the rows explicitly, in the same
-- transaction as the task, which is how debezium_offsets and debezium_schema_histories work.
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
