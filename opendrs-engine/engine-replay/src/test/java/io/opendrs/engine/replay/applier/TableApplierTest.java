package io.opendrs.engine.replay.applier;

import static io.opendrs.engine.replay.TestEvents.row;
import static org.assertj.core.api.Assertions.assertThat;

import io.opendrs.engine.replay.ReplayConfig;
import io.opendrs.engine.replay.connection.ConnectionProvider;
import io.opendrs.engine.replay.context.TaskContext;
import io.opendrs.engine.replay.event.ChangeEvent;
import io.opendrs.engine.replay.event.Operation;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * {@link TableApplier}: a batch is partitioned by table, each partition applied by one dedicated
 * (here: faked) executor, and apply() is a barrier that returns only when every partition
 * finished.
 */
class TableApplierTest {

    @Test
    void partitionsByTableAndWaitsForAll() throws Exception {
        List<List<ChangeEvent>> received = new CopyOnWriteArrayList<>();
        List<Applier<Collection<ChangeEvent>>> executors = new ArrayList<>();
        ReplayConfig config = ReplayConfig.builder()
                .connectionProvider(new UnreachableConnectionProvider())
                .tableApplierConcurrency(2)
                .build();
        TableApplier applier = new TableApplier(() -> true, config, () -> {
            RecordingExecutor executor = new RecordingExecutor(received);
            executors.add(executor);
            return executor;
        });
        applier.prepare(TaskContext.empty());

        List<ChangeEvent> batch = List.of(
                row(Operation.READ, "s", "a", 1, null),
                row(Operation.READ, "s", "a", 2, null),
                row(Operation.READ, "s", "b", 3, null),
                row(Operation.READ, "s", "c", 4, null));
        applier.apply(batch);

        // Every partition completed before apply() returned (barrier).
        assertThat(received).hasSize(3);
        int rows = received.stream().mapToInt(List::size).sum();
        assertThat(rows).isEqualTo(4);
        // Each partition is single-table: one executor never sees two tables' rows in one call.
        for (List<ChangeEvent> partition : received) {
            String table = ((io.opendrs.engine.replay.event.DataChangeEvent) partition.get(0))
                    .getTableId().getTable();
            assertThat(partition).allMatch(e ->
                    ((io.opendrs.engine.replay.event.DataChangeEvent) e).getTableId().getTable().equals(table));
        }
        applier.release();
    }

    private static final class UnreachableConnectionProvider implements ConnectionProvider {
        @Override
        public Connection getConnection() {
            throw new UnsupportedOperationException("tests never open a connection");
        }

        @Override
        public void close() {
        }
    }

    private static class RecordingExecutor implements Applier<Collection<ChangeEvent>> {
        private final List<List<ChangeEvent>> sink;

        RecordingExecutor(List<List<ChangeEvent>> sink) {
            this.sink = sink;
        }

        @Override
        public void prepare(TaskContext taskContext) {
        }

        @Override
        public void apply(Collection<ChangeEvent> events) {
            sink.add(List.copyOf(events));
        }

        @Override
        public void release() {
        }
    }
}
