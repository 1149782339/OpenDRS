-- Per-partition start and consume positions for incremental data check.
-- start_offset is the replay group's committed offset (or topic beginning if none)
-- snapshotted before the full check. consumed_offset is the check's own next-to-read
-- position, used to resume after stop. Kafka group opendrs.datacheck.{task.public_id}
-- is observability only; this table is the source of truth.

CREATE TABLE datacheck_offsets (
  datacheck_task_id BIGINT        NOT NULL,
  topic             VARCHAR(255)  NOT NULL,
  partition_id      INT           NOT NULL,
  start_offset      BIGINT        NOT NULL,
  consumed_offset   BIGINT        NULL,
  updated_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (datacheck_task_id, topic, partition_id),
  CONSTRAINT fk_datacheck_offset_task FOREIGN KEY (datacheck_task_id)
    REFERENCES datacheck_tasks (id)
);
