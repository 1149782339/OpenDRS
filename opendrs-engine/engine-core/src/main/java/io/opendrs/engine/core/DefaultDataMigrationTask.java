package io.opendrs.engine.core;

import io.opendrs.engine.capture.CdcEngine;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Runs one CDC engine on a worker thread. The engine is created inside the worker so the metadata
 * {@code DataSource} binding and its unbind both happen on the thread that runs the engine.
 */
final class DefaultDataMigrationTask extends AbstractMigrationTask implements DataMigrationTask {

    private final long taskId;
    private final Function<Runnable, CdcEngine> engineFactory;
    private final Consumer<DataMigrationTaskPhase> phaseListener;

    /** Written on the worker thread, read from the coordinator thread. */
    private volatile DataMigrationTaskPhase phase = DataMigrationTaskPhase.FULL;

    private volatile CdcEngine engine;

    DefaultDataMigrationTask(
            long taskId,
            Function<Runnable, CdcEngine> engineFactory,
            Consumer<DataMigrationTaskPhase> phaseListener) {
        this.taskId = taskId;
        this.engineFactory = engineFactory;
        this.phaseListener = phaseListener;
    }

    void start(ExecutorService executor) {
        submit(executor, () -> {
            notifyPhase(DataMigrationTaskPhase.FULL);
            engine = engineFactory.apply(() -> enterPhase(DataMigrationTaskPhase.INCREMENTAL));
            if (stopRequested()) {
                // Stop arrived while the engine was being built, so onStopRequested() had nothing
                // to close. Close here instead: stop() is idempotent and also releases the
                // metadata binding. Never call run() on a closed engine.
                engine.stop();
                return;
            }
            engine.run();
            if (!stopRequested()) {
                // A streaming run has no natural end: returning without a stop means the engine
                // shut itself down, which is a failure of the run, not a completion.
                throw new IllegalStateException(
                        "CDC engine for task " + taskId + " ended without a stop request");
            }
        });
    }

    private void enterPhase(DataMigrationTaskPhase next) {
        phase = next;
        notifyPhase(next);
    }

    private void notifyPhase(DataMigrationTaskPhase current) {
        Consumer<DataMigrationTaskPhase> listener = phaseListener;
        if (listener != null) {
            listener.accept(current);
        }
    }

    @Override
    protected void onStopRequested() {
        CdcEngine running = engine;
        if (running != null) {
            running.stop();
        }
    }

    @Override
    public DataMigrationTaskPhase taskPhase() {
        return phase;
    }
}
