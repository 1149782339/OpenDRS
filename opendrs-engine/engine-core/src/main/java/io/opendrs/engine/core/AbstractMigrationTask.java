package io.opendrs.engine.core;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared lifecycle for the three task handles: one worker task, one terminal state, one latch.
 *
 * <p>Stopping is cooperative and never interrupts the worker. A data migration stops through
 * {@code CdcEngine.stop()}; a schema migration checks {@link #stopRequested()} between tables.
 * Interrupting the worker would abort a JDBC call mid-flight and leave the target in an unknown
 * state, so the handle only asks.
 */
abstract class AbstractMigrationTask implements MigrationTask {

    private final AtomicReference<TaskState> state = new AtomicReference<>(TaskState.STARTING);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final CountDownLatch done = new CountDownLatch(1);

    final void submit(ExecutorService executor, Runnable body) {
        executor.execute(() -> {
            if (stopRequested.get()) {
                complete(TaskState.STOPPED);
                return;
            }
            state.set(TaskState.RUNNING);
            try {
                body.run();
                complete(TaskState.COMPLETED);
            } catch (Throwable ex) {
                failure.set(ex);
                complete(TaskState.FAILED);
            }
        });
    }

    private void complete(TaskState terminal) {
        TaskState settled = stopRequested.get() && terminal == TaskState.COMPLETED
                ? TaskState.STOPPED
                : terminal;
        state.set(settled);
        done.countDown();
    }

    @Override
    public final TaskState state() {
        return state.get();
    }

    @Override
    public final Throwable failure() {
        return failure.get();
    }

    @Override
    public final void requestStop() {
        stopRequested.set(true);
        if (state.get() == TaskState.RUNNING) {
            state.set(TaskState.STOPPING);
        }
        onStopRequested();
    }

    @Override
    public final void await() throws InterruptedException {
        done.await();
    }

    /** Hook for the concrete task: stop the underlying engine, if one is already running. */
    protected void onStopRequested() {
    }

    protected final boolean stopRequested() {
        return stopRequested.get();
    }
}
