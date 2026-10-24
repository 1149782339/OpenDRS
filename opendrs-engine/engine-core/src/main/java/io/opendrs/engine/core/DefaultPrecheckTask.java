package io.opendrs.engine.core;

import io.opendrs.engine.precheck.PrecheckResults;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/** Runs one precheck pass on a worker thread; the result is available once it completes. */
final class DefaultPrecheckTask extends AbstractMigrationTask implements PrecheckTask {

    private final Supplier<PrecheckResults> work;
    private volatile PrecheckResults results;

    DefaultPrecheckTask(Supplier<PrecheckResults> work) {
        this.work = work;
    }

    void start(ExecutorService executor) {
        submit(executor, () -> results = work.get());
    }

    @Override
    public PrecheckResults results() {
        return results;
    }
}
