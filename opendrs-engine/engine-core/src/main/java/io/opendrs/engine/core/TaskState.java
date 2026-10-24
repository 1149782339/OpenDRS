package io.opendrs.engine.core;

import io.opendrs.engine.replay.SnapshotStrategy;

/**
 * Lifecycle of one job execution — the single state enum shared by the engine handles and the
 * service's persisted {@code state} column.
 *
 * <p>{@link #STARTING} covers both sides of the same idea: the service has dispatched the job but
 * the executor thread has not picked it up yet, and the engine has created the handle but its
 * worker has not started. {@link #COMPLETED} is reachable only for finite jobs: the
 * schema-migration run and a FULL_ONLY data run (the connector takes the snapshot and stops by
 * itself); a long-running CDC task ends through {@link #STOPPED} or {@link #FAILED}. Precheck
 * never touches this column at all — it has not started migrating anything, so success is
 * expressed as {@code phase = PRECHECKED} with {@code state = NULL}, and failure leaves
 * {@code state = FAILED} so the user can fix the cause and re-run. Like {@link #TERMINATED},
 * {@link #COMPLETED} is terminal: start refuses and the row may be deleted.
 *
 * <p>{@link #STOPPED} means <em>paused</em> (暂停): resumable through start. {@link #TERMINATED}
 * is the user-declared end of the task (结束): resources released, start refuses, only then may the
 * row be deleted. The two wind-down states are distinct values — {@link #STOPPING} is 暂停中,
 * {@link #TERMINATING} is 结束中 — so the UI needs no side channel to tell them apart.
 */
public enum TaskState {
    STARTING,
    RUNNING,
    STOPPING,
    TERMINATING,
    STOPPED,
    COMPLETED,
    FAILED,
    TERMINATED
}
