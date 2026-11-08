-- Pipeline public_id (UUID) for Kafka topic/group binding, plus applied-offset table for the
-- KAFKA transport path. Internal PK/FK (bigint id) unchanged; topic/group are derived, not stored.
--
-- debezium_offsets (KAFKA)  = produce progress
-- Kafka consumer group      = read progress
-- pipeline_applied_offsets  = target apply progress

ALTER TABLE task_pipelines
  ADD COLUMN public_id VARCHAR(36) NULL AFTER id;

UPDATE task_pipelines
SET public_id = UUID()
WHERE public_id IS NULL;

ALTER TABLE task_pipelines
  MODIFY COLUMN public_id VARCHAR(36) NOT NULL,
  ADD UNIQUE KEY uk_pipeline_public_id (public_id);

CREATE TABLE pipeline_applied_offsets (
  pipeline_id         BIGINT        NOT NULL,
  source_offset_json  TEXT          NULL,
  kafka_topic         VARCHAR(255)  NULL,
  kafka_partition     INT           NULL,
  kafka_offset        BIGINT        NULL,
  updated_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (pipeline_id),
  CONSTRAINT fk_applied_pipeline FOREIGN KEY (pipeline_id) REFERENCES task_pipelines (id)
);
