-- Task log shown on the detail page. Written from two sides: the service records lifecycle events
-- it owns (start, phase change, stop, failure, precheck summary) and buffers what the engine
-- reports through MigrationLogListener.
--
-- level is INFO / WARNING / ERROR. It is a VARCHAR rather than an enum so adding a level later does
-- not need a schema change; the service validates the value it writes.
--
-- No ON DELETE CASCADE: MigrationTaskService.delete() removes the rows explicitly, in the same
-- transaction as the task, which is how debezium_offset and debezium_schema_history already work.
CREATE TABLE migration_log (
  id         BIGINT        NOT NULL AUTO_INCREMENT,
  task_id    BIGINT        NOT NULL,
  logged_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  level      VARCHAR(16)   NOT NULL,
  message    VARCHAR(2000) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_log_task_id (task_id, id),
  CONSTRAINT fk_log_task FOREIGN KEY (task_id) REFERENCES migration_task (id)
);
