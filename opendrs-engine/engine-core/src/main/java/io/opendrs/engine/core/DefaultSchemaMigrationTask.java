package io.opendrs.engine.core;

import io.opendrs.jdbc.metadata.Table;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/** Runs {@link SchemaMigrator#migrate} on a worker thread and exposes its outcome. */
final class DefaultSchemaMigrationTask extends AbstractMigrationTask implements SchemaMigrationTask {

    private final Supplier<SchemaMigrator.Result> work;
    private volatile List<Table> created = List.of();
    private volatile List<Table> skipped = List.of();

    DefaultSchemaMigrationTask(Supplier<SchemaMigrator.Result> work) {
        this.work = work;
    }

    void start(ExecutorService executor) {
        submit(executor, () -> {
            SchemaMigrator.Result result = work.get();
            created = result.created();
            skipped = result.skipped();
        });
    }

    @Override
    public List<Table> created() {
        return created;
    }

    @Override
    public List<Table> skipped() {
        return skipped;
    }
}
