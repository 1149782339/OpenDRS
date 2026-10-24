package io.opendrs.engine.replay.applier;

import static io.opendrs.engine.replay.TestEvents.row;
import static io.opendrs.engine.replay.TestEvents.txMark;
import static org.assertj.core.api.Assertions.assertThat;

import io.opendrs.engine.replay.ReplayConfig;
import io.opendrs.engine.replay.connection.ConnectionProvider;
import io.opendrs.engine.replay.context.TaskContext;
import io.opendrs.engine.replay.event.ApplierEvent;
import io.opendrs.engine.replay.event.ChangeEvent;
import io.opendrs.engine.replay.event.Operation;
import io.opendrs.engine.replay.event.TransactionEvent;
import io.opendrs.engine.replay.util.ErrorHandler;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link RoutingApplier}: snapshot rows ({@code Operation.READ}, no transaction context) go to
 * the table applier; binlog events go to the transaction applier; a batch straddling both
 * phases routes each event to the right executor.
 */
class RoutingApplierTest {

    private final List<RoutingApplier> routers = new ArrayList<>();

    @AfterEach
    void releaseRouters() {
        for (RoutingApplier router : routers) {
            router.release();
        }
    }

    @Test
    void readRowsGoToTableApplierBinlogEventsToTransactionApplier() throws Exception {
        List<List<ChangeEvent>> tableSide = new CopyOnWriteArrayList<>();
        List<List<ChangeEvent>> txSide = new CopyOnWriteArrayList<>();
        RoutingApplier router = router(tableSide, txSide, 2, 2);

        router.apply(List.of(
                row(Operation.READ, "s", "a", 1, null),
                row(Operation.READ, "s", "a", 2, null),
                txMark("t1", TransactionEvent.Status.BEGIN),
                row(Operation.CREATE, "s", "b", 3, "t1"),
                row(Operation.CREATE, "s", "b", 4, "t1"),
                txMark("t1", TransactionEvent.Status.END)));

        assertThat(tableSide).hasSize(1);
        assertThat(tableSide.get(0)).hasSize(2);
        assertThat(txSide).hasSize(1);
        // the routed transaction keeps its BEGIN/END markers: BEGIN + 2 rows + END
        assertThat(txSide.get(0)).hasSize(4);
    }

    @Test
    void emptyInputIsANoOp() throws Exception {
        List<List<ChangeEvent>> tableSide = new CopyOnWriteArrayList<>();
        List<List<ChangeEvent>> txSide = new CopyOnWriteArrayList<>();
        RoutingApplier router = router(tableSide, txSide, 1, 1);
        router.apply(List.of());
        router.apply(new ArrayList<>());
        assertThat(tableSide).isEmpty();
        assertThat(txSide).isEmpty();
    }

    private RoutingApplier router(
            List<List<ChangeEvent>> tableSide,
            List<List<ChangeEvent>> txSide,
            int tableWorkers,
            int txWorkers) {
        ReplayConfig config = ReplayConfig.builder()
                .connectionProvider(new UnreachableConnectionProvider())
                .tableApplierConcurrency(tableWorkers)
                .txApplierConcurrency(txWorkers)
                .transactionBufferSize(16)
                .build();
        TableApplier tableApplier = new TableApplier(() -> true, config,
                () -> new RecordingExecutor(tableSide));
        ErrorHandler errorHandler = new ErrorHandler();
        BlockingQueue<ApplierEvent> queue = new ArrayBlockingQueue<>(16);
        AtomicBoolean running = new AtomicBoolean(true);
        TransactionDispatcher dispatcher = new TransactionDispatcher(
                queue,
                running::get,
                errorHandler,
                config,
                d -> new ApplierWorker(d, running::get, errorHandler, config, new RecordingExecutor(txSide)));
        TransactionApplier txApplier = new TransactionApplier(queue, dispatcher);
        RoutingApplier router = new RoutingApplier(tableApplier, txApplier);
        router.prepare(TaskContext.empty());
        routers.add(router);
        return router;
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
