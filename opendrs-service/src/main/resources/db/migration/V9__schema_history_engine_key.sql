-- Bidirectional sync runs two Debezium engines (fwd/rev) under one task id. Schema history rows
-- must be scoped per engine or the two directions race on (task_id, record_seq) and mix up
-- each other's table schemas on recovery.

ALTER TABLE debezium_schema_history
    ADD COLUMN engine_key VARCHAR(128) NOT NULL DEFAULT '' AFTER task_id;

-- MySQL refuses to drop an index that backs a foreign-key check, so the constraint comes off
-- first and goes back on after the unique key has been re-scoped.
ALTER TABLE debezium_schema_history
    DROP FOREIGN KEY fk_history_task;

ALTER TABLE debezium_schema_history
    DROP INDEX uk_history_task_seq;

ALTER TABLE debezium_schema_history
    ADD UNIQUE KEY uk_history_engine_seq (task_id, engine_key, record_seq);

ALTER TABLE debezium_schema_history
    ADD CONSTRAINT fk_history_task FOREIGN KEY (task_id) REFERENCES migration_task (id);
