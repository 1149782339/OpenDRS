package io.opendrs.common.progress;

/**
 * Full-load (initial snapshot) completion callbacks. The engine reports, per snapshot and per
 * table, after all of that table's rows are committed on the target — never before.
 *
 * <p>Presence is the done mark: the persistence layer stores one row per finished
 * {@code (pipeline, table)} and the resume planner derives "still owes a snapshot" as
 * {@code current selection − stored rows}. There is intentionally no table-list or pending
 * state here: a crashed run simply leaves fewer rows behind, and a resume re-expands the
 * task's own selection instead of trusting a stale snapshot-time list.
 *
 * <p>Callbacks run on the Debezium engine thread, inside the consumer's batch handling:
 * implementations must be quick and must not block (do the metadata-DB write and return).
 */
public interface FullLoadCheckpointListener {

    FullLoadCheckpointListener NONE = new FullLoadCheckpointListener() {
    };

    /**
     * All rows of {@code table} are committed on the target. {@code rowCount} is the scanned row
     * count Debezium reported, informational only. {@code pipelineId} identifies the directional
     * pipeline (a bidirectional task checkpoints its two pipelines independently).
     */
    default void onTableFinished(long pipelineId, String table, long rowCount) {
    }
}
