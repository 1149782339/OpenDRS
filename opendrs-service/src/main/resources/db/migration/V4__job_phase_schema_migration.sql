-- SCHEMA_SNAPSHOT used to cover two engine calls (structure + snapshot). The snapshot now has its
-- own phase (FULL), so the old value only ever means "structure migration is the current step".

UPDATE migration_task SET job_phase = 'SCHEMA_MIGRATION' WHERE job_phase = 'SCHEMA_SNAPSHOT';
