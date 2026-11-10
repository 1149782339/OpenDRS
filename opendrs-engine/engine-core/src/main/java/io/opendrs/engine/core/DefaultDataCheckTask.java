package io.opendrs.engine.core;

import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Runs one data-check pass on a worker thread; results are persisted by the work itself. */
final class DefaultDataCheckTask extends AbstractMigrationTask implements DataCheckTask {

    private final Consumer<BooleanSupplier> work;

    DefaultDataCheckTask(Consumer<BooleanSupplier> work) {
        this.work = work;
    }

    void start(ExecutorService executor) {
        submit(executor, () -> work.accept(this::stopRequested));
    }
}
