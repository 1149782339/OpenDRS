package io.opendrs.engine.core;

/**
 * Lifecycle of one job execution — the single state enum shared by the engine handles and the
 * service's persisted {@code job_state} column.
 *
 * <p>{@link #STARTING} covers both sides of the same idea: the service has dispatched the job but
 * the executor thread has not picked it up yet, and the engine has created the handle but its
 * worker has not started. {@link #COMPLETED} is only reachable for finite jobs (schema migration
 * and precheck); a CDC run ends through {@link #STOPPED} or {@link #FAILED}.
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
