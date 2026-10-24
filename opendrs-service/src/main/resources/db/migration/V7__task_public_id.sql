-- Business-facing UUID alongside the internal auto-increment id (ovh/utask's public_id pattern):
-- bigint id stays the PK and the target of all internal FKs; public_id is the stable identifier
-- exposed through the API and UI. Existing rows are disposable: drop children first (their FKs
-- have no cascade), then the tasks, then widen the counter to 1000 so default names DRS-xxxx
-- start at a product-looking number.
DELETE FROM migration_log;
DELETE FROM debezium_offset;
DELETE FROM debezium_schema_history;
DELETE FROM migration_task;

ALTER TABLE migration_task
  ADD COLUMN public_id VARCHAR(36) NOT NULL AFTER name,
  ADD UNIQUE KEY uk_task_public_id (public_id);

ALTER TABLE migration_task AUTO_INCREMENT = 1000;
