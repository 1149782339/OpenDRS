package io.opendrs.engine.replay.applier;

import static io.opendrs.engine.replay.TestEvents.row;
import static io.opendrs.engine.replay.TestEvents.txMark;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opendrs.engine.replay.ReplayConfig;
import io.opendrs.engine.replay.connection.ConnectionProvider;
import io.opendrs.engine.replay.context.TaskContext;
import io.opendrs.engine.replay.event.ApplierEvent;
import io.opendrs.engine.replay.event.ChangeEvent;
import io.opendrs.engine.replay.event.DataChangeEvent;
import io.opendrs.engine.replay.event.Operation;
import io.opendrs.engine.replay.event.TransactionEvent;
import io.opendrs.engine.replay.exception.ApplierException;
import io.opendrs.engine.replay.util.ErrorHandler;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Transaction assembly + dispatch contract of {@link TransactionApplier}, against real
 * dispatcher/worker/queue machinery with the JDBC executor faked: one apply() call returns
 * only after every transaction received so far has been "applied" (recorded).
 */
class TransactionApplierTest {

    private final List<TransactionApplier> appliers = new ArrayList<>();

    @AfterEach
    void releaseAppliers() {
        for (TransactionApplier applier : appliers) {
            applier.release();
        }
    }

    @Test
    void assemblesBeginEndPairsIntoOneUnit() throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        TransactionApplier applier = applier(2, executor);
        applier.apply(List.of(
                txMark("t1", TransactionEvent.Status.BEGIN),
                row(Operation.CREATE, "s", "a", 1, "t1"),
                row(Operation.CREATE, "s", "a", 2, "t1"),
                txMark("t1", TransactionEvent.Status.END)));
        assertThat(executor.transactions).hasSize(1);
        List<ChangeEvent> unit = executor.transactions.get(0);
        assertThat(unit).hasSize(4);
        assertThat(unit.get(0)).isInstanceOf(TransactionEvent.class);
        assertThat(((DataChangeEvent) unit.get(1)).getTransactionId()).isEqualTo("t1");
    }

    @Test
    void transactionsDispatchAsTheyCompleteAcrossBatches() throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        TransactionApplier applier = applier(2, executor);
        // t1 spans two apply() calls; nothing may dispatch before its END.
        applier.apply(List.of(
                txMark("t1", TransactionEvent.Status.BEGIN),
                row(Operation.CREATE, "s", "a", 1, "t1")));
        assertThat(executor.transactions).isEmpty();
        applier.apply(List.of(
                row(Operation.CREATE, "s", "a", 2, "t1"),
                txMark("t1", TransactionEvent.Status.END),
                txMark("t2", TransactionEvent.Status.BEGIN),
                row(Operation.CREATE, "s", "b", 3, "t2"),
                txMark("t2", TransactionEvent.Status.END)));
        assertThat(executor.transactions).hasSize(2);
        // 两个事务写的是不同表、writeset 不冲突，谁先完成由调度决定 —— 完成顺序
        // （= 记录顺序）不是契约，只能按内容匹配，不能按列表位置断言。
        List<ChangeEvent> t1 = unitEndingWith(executor.transactions, "t1");
        List<ChangeEvent> t2 = unitEndingWith(executor.transactions, "t2");
        assertThat(t1).hasSize(4);
        assertThat(((TransactionEvent) t1.get(3)).getTransactionId()).isEqualTo("t1");
        assertThat(t2).hasSize(3);
        assertThat(((TransactionEvent) t2.get(2)).getTransactionId()).isEqualTo("t2");
    }

    /** 找出以指定事务的 END 标记收尾的那个单元；不存在则断言失败。 */
    private static List<ChangeEvent> unitEndingWith(List<List<ChangeEvent>> units, String transactionId) {
        return units.stream()
                .filter(unit -> {
                    ChangeEvent last = unit.get(unit.size() - 1);
                    return last instanceof TransactionEvent
                            && ((TransactionEvent) last).getStatus() == TransactionEvent.Status.END
                            && transactionId.equals(((TransactionEvent) last).getTransactionId());
                })
                .findFirst()
                .orElseThrow(() -> new AssertionError("no unit ends with transaction " + transactionId));
    }

    @Test
    void eventsWithoutTransactionDispatchImmediatelyAsSingletons() throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        TransactionApplier applier = applier(2, executor);
        applier.apply(List.of(row(Operation.READ, "s", "a", 1, null)));
        assertThat(executor.transactions).hasSize(1);
        assertThat(executor.transactions.get(0)).hasSize(1);
    }

    @Test
    void applyReturnsOnlyAfterInFlightTransactionsComplete() throws Exception {
        GateExecutor executor = new GateExecutor();
        TransactionApplier applier = applier(1, executor);
        List<List<ChangeEvent>> applied = new CopyOnWriteArrayList<>();
        AtomicBoolean applyReturned = new AtomicBoolean();
        Thread engine = new Thread(() -> {
            try {
                applier.apply(List.of(
                        txMark("t1", TransactionEvent.Status.BEGIN),
                        row(Operation.CREATE, "s", "a", 1, "t1"),
                        txMark("t1", TransactionEvent.Status.END)));
                applied.addAll(executor.recordedEvents());
                applyReturned.set(true);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        engine.start();
        // The apply call must still be blocked while the executor holds the transaction.
        Thread.sleep(200);
        assertThat(applyReturned).isFalse();
        executor.openGate();
        engine.join(TimeUnit.SECONDS.toMillis(5));
        assertThat(applyReturned).isTrue();
        assertThat(applied).hasSize(1);
    }

    @Test
    void workerFailureSurfacesAsApplyFailure() {
        RecordingExecutor executor = new RecordingExecutor();
        executor.failOnCall.set(1);
        TransactionApplier applier = applier(1, executor);
        assertThatThrownBy(() -> applier.apply(List.of(row(Operation.CREATE, "s", "a", 1, null))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("parallel apply failed");
    }

    /** Builds a real chain (queue → dispatcher → workers) with the JDBC executor faked. */
    private TransactionApplier applier(int workers, Applier<Collection<ChangeEvent>> inner) {
        ReplayConfig config = ReplayConfig.builder()
                .connectionProvider(new UnreachableConnectionProvider())
                .txApplierConcurrency(workers)
                .tableApplierConcurrency(1)
                .transactionBufferSize(16)
                .build();
        ErrorHandler errorHandler = new ErrorHandler();
        BlockingQueue<ApplierEvent> queue = new ArrayBlockingQueue<>(16);
        AtomicBoolean running = new AtomicBoolean(true);
        TransactionDispatcher dispatcher = new TransactionDispatcher(
                queue,
                running::get,
                errorHandler,
                config,
                d -> new ApplierWorker(d, running::get, errorHandler, config, inner));
        TransactionApplier applier = new TransactionApplier(queue, dispatcher);
        applier.prepare(TaskContext.empty());
        appliers.add(applier);
        return applier;
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

    /** Applies by recording the fed transaction's event list, one entry per apply() call. */
    private static class RecordingExecutor implements Applier<Collection<ChangeEvent>> {
        final List<List<ChangeEvent>> transactions = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger failOnCall = new AtomicInteger(-1);
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public void prepare(TaskContext taskContext) {
        }

        @Override
        public void apply(Collection<ChangeEvent> events) throws ApplierException {
            int call = calls.incrementAndGet();
            if (call == failOnCall.get()) {
                throw new ApplierException("injected failure on call " + call);
            }
            transactions.add(List.copyOf(events));
        }

        @Override
        public void release() {
        }
    }

    /** Holds the first transaction until the test opens the gate, proving the barrier. */
    private static class GateExecutor implements Applier<Collection<ChangeEvent>> {
        private final CountDownLatch gate = new CountDownLatch(1);
        private final List<List<ChangeEvent>> recorded = new ArrayList<>();

        @Override
        public void prepare(TaskContext taskContext) {
        }

        @Override
        public void apply(Collection<ChangeEvent> events) throws ApplierException {
            try {
                if (!gate.await(5, TimeUnit.SECONDS)) {
                    throw new ApplierException("gate never opened");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApplierException("interrupted", e);
            }
            synchronized (this) {
                recorded.add(List.copyOf(events));
            }
        }

        void openGate() {
            gate.countDown();
        }

        @Override
        public void release() {
        }

        synchronized List<List<ChangeEvent>> recordedEvents() {
            return List.copyOf(recorded);
        }
    }
}
