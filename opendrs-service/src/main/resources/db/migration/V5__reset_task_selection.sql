-- The task selection model changed shape. tables_json used to be
--   {"objects":[{"schema":"hr","tables":["emp"],"allTables":true,"excludeTables":["tmp_*"]}]}
-- and is now
--   {"schemas":["hr"],"tables":[{"schema":"hr","table":"emp"}],"excludeSchemas":[],"excludeTables":[]}
--
-- Old rows cannot be read back (unknown properties fail deserialization, and the old model cannot
-- express "no filter at all"), so they are dropped along with the Debezium offsets and schema
-- history that belong to them. There is no migration path because the old selections do not all
-- map onto the new ones; the user accepted deleting them.

DELETE FROM debezium_offset;

DELETE FROM debezium_schema_history;

DELETE FROM migration_task;
