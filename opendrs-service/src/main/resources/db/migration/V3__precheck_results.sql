-- Persist accumulating precheck CheckResult lists for async POST + GET polling.

ALTER TABLE migration_task
  ADD COLUMN precheck_results_json JSON NULL AFTER options_json;
