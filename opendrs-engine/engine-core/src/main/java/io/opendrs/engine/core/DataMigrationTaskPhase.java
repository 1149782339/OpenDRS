package io.opendrs.engine.core;

/**
 * Where a data migration currently is inside the pipeline. Only data migration has phases: schema
 * migration is one finite operation and precheck is one finite pass, so neither exposes one.
 *
 * <p>Unlike {@link TaskState}, this always has a value. A data run is defined as "copy the rows,
 * then follow the change log", so it starts in {@link #FULL} the moment the handle exists and can
 * only move forward to {@link #INCREMENTAL}. It is in-memory runtime state: the engine never
 * persists it, the service maps it onto its own {@code JobPhase} when it wants to.
 *
 * <p>This axis is independent of {@link TaskState}: a run can be {@code RUNNING} in either phase,
 * and it can fail or be stopped in either phase.
 */
public enum DataMigrationTaskPhase {

    /** Initial load: the snapshot is copying rows into the target. */
    FULL,

    /** Streaming: the snapshot is done and only the change log is being applied. */
    INCREMENTAL
}
