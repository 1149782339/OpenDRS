-- Unified task configuration: the table selection moves INTO options_json, so the
-- migration_task row carries exactly one config document. Existing rows are wiped
-- (dev-stage decision — selections from before this shape are not worth converting).
-- Child tables first: debezium_offset, debezium_schema_history and migration_log
-- all FK into migration_task.
DELETE FROM migration_log;
DELETE FROM debezium_schema_history;
DELETE FROM debezium_offset;
DELETE FROM migration_task;

ALTER TABLE migration_task
  DROP COLUMN tables_json,
  MODIFY COLUMN options_json JSON NOT NULL;
