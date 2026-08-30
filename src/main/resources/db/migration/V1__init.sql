-- OpenDRS metadata store.
-- debezium_offset / debezium_schema_history 预留给后续 Debezium OffsetBackingStore 与 DatabaseHistory，v1 不写入。

CREATE TABLE connection_info (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  name          VARCHAR(128) NOT NULL,
  type          VARCHAR(16)  NOT NULL COMMENT 'ORACLE / MYSQL',
  host          VARCHAR(255) NOT NULL,
  port          INT          NOT NULL,
  db_name       VARCHAR(128) NOT NULL,
  username      VARCHAR(128) NOT NULL,
  password      VARCHAR(512) NOT NULL,
  extra_json    JSON         NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_connection_name (name)
);

CREATE TABLE migration_task (
  id                    BIGINT       NOT NULL AUTO_INCREMENT,
  name                  VARCHAR(128) NOT NULL,
  mode                  VARCHAR(32)  NOT NULL,
  state                 VARCHAR(32)  NOT NULL,
  source_connection_id  BIGINT       NOT NULL,
  target_connection_id  BIGINT       NOT NULL,
  tables_json           JSON         NOT NULL,
  options_json          JSON         NULL,
  tables_total          INT          NOT NULL DEFAULT 0,
  tables_done           INT          NOT NULL DEFAULT 0,
  rows_done             BIGINT       NOT NULL DEFAULT 0,
  lag_ms                BIGINT       NULL,
  error_message         VARCHAR(1024) NULL,
  created_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_task_name (name),
  KEY idx_task_state (state),
  CONSTRAINT fk_task_source_conn FOREIGN KEY (source_connection_id) REFERENCES connection_info (id),
  CONSTRAINT fk_task_target_conn FOREIGN KEY (target_connection_id) REFERENCES connection_info (id)
);

CREATE TABLE debezium_offset (
  id          BIGINT   NOT NULL AUTO_INCREMENT,
  task_id     BIGINT   NOT NULL,
  offset_key  TEXT     NOT NULL,
  offset_val  TEXT     NULL,
  updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_offset_task_key (task_id, offset_key(255)),
  CONSTRAINT fk_offset_task FOREIGN KEY (task_id) REFERENCES migration_task (id)
);

CREATE TABLE debezium_schema_history (
  id            BIGINT     NOT NULL AUTO_INCREMENT,
  task_id       BIGINT     NOT NULL,
  record_seq    INT        NOT NULL,
  history_data  MEDIUMTEXT NOT NULL,
  created_at    DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_history_task_seq (task_id, record_seq),
  CONSTRAINT fk_history_task FOREIGN KEY (task_id) REFERENCES migration_task (id)
);
