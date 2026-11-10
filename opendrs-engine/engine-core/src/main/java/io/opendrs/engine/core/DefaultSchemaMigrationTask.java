package io.opendrs.engine.core;

import io.opendrs.engine.common.metadata.TableId;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/** Runs one schema migration on a worker thread and exposes its outcome. */
final class DefaultSchemaMigrationTask extends AbstractMigrationTask implements SchemaMigrationTask {

    private final Supplier<SchemaMigrationOutcome> work;
    private volatile List<TableId> created = List.of();
    private volatile List<TableId> skipped = List.of();

    DefaultSchemaMigrationTask(Supplier<SchemaMigrationOutcome> work) {
        this.work = work;
    }

    void start(ExecutorService executor) {
        submit(executor, () -> {
            SchemaMigrationOutcome result = work.get();
            created = result.created();
            skipped = result.skipped();
        });
    }

    @Override
    public List<TableId> created() {
        return created;
    }

    @Override
    public List<TableId> skipped() {
        return skipped;
    }
}
