package io.opendrs.migration.domain;

/**
 * Pipeline location, owned by the service and rendered by the UI. Independent of
 * {@link io.opendrs.engine.core.TaskState} (thread lifecycle) and of the engine's own
 * {@link io.opendrs.engine.core.DataMigrationTaskPhase}: this axis also covers the precheck steps,
 * which the engine has no concept of.
 *
 * <p>{@code CREATED → PRECHECKING → PRECHECKED → SCHEMA_MIGRATION → FULL → INCREMENTAL}.
 *
 * <p>One-way full + incremental tasks load into PK-only target tables and run two extra steps:
 * {@code SCHEMA_MIGRATION → FULL_PREPARE → FULL → POST_FULL → INCREMENTAL}. {@code FULL_PREPARE}
 * records and drops the secondary indexes, unique keys and foreign keys of the target tables;
 * {@code POST_FULL} rebuilds the plain indexes once the snapshot is in, before any change is
 * replayed. Unique and foreign keys come back during {@code INCREMENTAL}, once replay has passed
 * the source position recorded at the end of the snapshot.
 *
 * <p>From {@code SCHEMA_MIGRATION} on, each phase maps to exactly one engine call:
 * {@code SCHEMA_MIGRATION} is the structure run, {@code FULL} is the data run while the snapshot is
 * copying rows, {@code INCREMENTAL} is that same data run once it follows the log instead. The
 * phase is advanced <em>before</em> the call it describes, so a crash leaves the row pointing at the
 * step that has to be redone — re-entering {@code SCHEMA_MIGRATION} is safe because the structure
 * migration skips tables that already exist.
 */
public enum MigrationPhase {

    /** Task row exists, nothing has run yet. */
    CREATED,

    /** Precheck thread running. */
    PRECHECKING,

    /** Precheck passed; waiting for the user to confirm the migration. */
    PRECHECKED,

    /** Structure run in progress: creating target tables, indexes and constraints. */
    SCHEMA_MIGRATION,

    /**
     * Before the full load: secondary objects of the target tables recorded, then dropped; target
     * table policy (truncate / refuse non-empty tables) applied. Re-entrant.
     */
    FULL_PREPARE,

    /** Data run in progress, still in its full-load half. */
    FULL,

    /**
     * Snapshot loaded, replay not started yet: plain secondary indexes rebuilt and statistics
     * refreshed. Re-entrant.
     */
    POST_FULL,

    /** Data run in progress, snapshot done, following the change log. */
    INCREMENTAL
}
