ALTER TABLE datacheck_table_tasks
  ADD COLUMN rows_checked BIGINT NULL AFTER finished_at,
  ADD COLUMN last_check_at DATETIME NULL AFTER rows_checked,
  ADD COLUMN waiting_for_replay TINYINT(1) NOT NULL DEFAULT 0 AFTER last_check_at;
