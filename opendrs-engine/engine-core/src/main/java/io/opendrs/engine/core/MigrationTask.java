package io.opendrs.engine.core;

/**
 * Handle for one engine execution. Created by {@link MigrationEngine}, it owns the worker thread
 * that performs the work, exposes the lifecycle through {@link #state()} and stops cooperatively
 * through {@link #requestStop()}.
 *
 * <p>One handle is <em>one run</em>: a stopped data migration can be started again, and that
 * produces a new handle. The handle is not the task entity — the service keeps that in its own
 * domain model.
 */
public interface MigrationTask {

    /** Current lifecycle state; never {@code null}. */
    TaskState state();

    /** The failure that moved the run to {@link TaskState#FAILED}; {@code null} otherwise. */
    Throwable failure();

    /**
     * Cooperative stop. Idempotent, safe to call before the worker starts and after it finished.
     * A stopped run ends in {@link TaskState#STOPPED}.
     */
    void requestStop();

    /**
     * Blocks until the run reaches a terminal state ({@code STOPPED}, {@code COMPLETED} or
     * {@code FAILED}).
     */
    void await() throws InterruptedException;
}
