package io.opendrs.engine.core;

import io.opendrs.common.metadata.DatabaseType;

/**
 * The single entry point of the migration engine — the black box. The engine owns no Spring, no
 * persistence, no REST/DTO concepts; the service assembles the engine and translates mechanism
 * exceptions into its own policy exceptions.
 *
 * <p>Every operation returns immediately with a handle. Blocking work (expanding the selector,
 * opening connections, running the capture) happens on the engine's own worker thread, so callers
 * never park a request or job thread inside the engine.
 */
public interface MigrationEngine {

    /**
     * Whether a precheck profile exists for this direction (fail-fast before starting a run).
     * Precheck is a property of the pair, not of one endpoint: a check that only makes sense for
     * MySQL sources cannot be answered by knowing the target type alone.
     */
    boolean supportsPrecheck(DatabaseType source, DatabaseType target);

    /**
     * Creates target tables, indexes and constraints. Does not move rows and does not start CDC.
     * Existing target tables are skipped, so the operation is idempotent.
     */
    SchemaMigrationTask migrateSchema(SchemaMigrationParam param);

    /**
     * Runs snapshot plus CDC for the selected tables. The target structure must already exist:
     * missing tables fail the run before the snapshot starts.
     */
    DataMigrationTask migrateData(DataMigrationParam param);

    /** Expands the selection and runs the direction's precheck checklist. */
    PrecheckTask precheck(PrecheckParam param);
}
