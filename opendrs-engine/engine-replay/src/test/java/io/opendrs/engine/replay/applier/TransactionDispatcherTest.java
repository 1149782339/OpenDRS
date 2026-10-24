package io.opendrs.engine.replay.applier;

import static io.opendrs.engine.replay.TestEvents.row;
import static io.opendrs.engine.replay.TestEvents.txMark;
import static org.assertj.core.api.Assertions.assertThat;

import io.opendrs.engine.replay.ReplayConfig;
import io.opendrs.engine.replay.connection.ConnectionProvider;
import io.opendrs.engine.replay.context.TaskContext;
import io.opendrs.engine.replay.event.ApplierEvent;
import io.opendrs.engine.replay.event.ChangeEvent;
import io.opendrs.engine.replay.event.DataChangeEvent;
import io.opendrs.engine.replay.event.Operation;
import io.opendrs.engine.replay.event.TransactionEvent;
import io.opendrs.engine.replay.util.ErrorHandler;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link TransactionDispatcher} watermark protocol: a transaction whose {@code lastCommitted} points at a
 * conflicting predecessor may not be fed before that predecessor completes, while non-conflicting
 * transactions pass through and overlap freely.
 *
 * <p>Events are hand-built ({@code sequenceNumber}/{@code lastCommitted} written literally) and put
 * into the queue in ascending sequence order — the order {@link TransactionApplier} guarantees in
 * production. Ascending order matters twice over: the dispatcher is single-threaded FIFO and
 * waits on the watermark <em>after</em> taking an event, so a conflicting event placed early
 * head-of-line blocks everything behind it; and {@code maxProcessedSeqNo} is the last-fed sequence,
 * which is only correct while feeds are ascending.
 *
 * <p>The sequence assignment in {@link #mixedDependencyGraphSchedulesSerialAndConcurrentSegments}
 * is therefore part of the scenario: free transactions and the short B chain carry the lower
 * sequence numbers (fed and running while the long A-chain links are still queued), the A-chain
 * conflicting pair carries the higher ones.
 */
class TransactionDispatcherTest {

    private final List<TransactionDispatcher> dispatchers = new ArrayList<>();

    @AfterEach
    void releaseDispatchers() {
        for (TransactionDispatcher dispatcher : dispatchers) {
            dispatcher.stop();
            dispatcher.release();
        }
    }

    /**
     * Dependency graph, four workers:
     *
     * <pre>
     *   free:        tx6 ─┐
     *               tx7 ─┤ concurrent at t=0
     *   B chain:   tx4 ──conflict── tx5   (80ms each)
     *   A chain:   tx1 ──conflict── tx2 ──conflict── tx3   (200ms each)
     * </pre>
     *
     * Expected timeline: tx6/tx7 end ~50ms, tx4 ~80, tx5 ~160 — the whole B chain plus both free
     * transactions finish inside the window of A's first link (~280ms); tx2 may not start before
     * tx1 completed; tx3 not before tx2. Conflicting segments serialize, everything else overlaps.
     */
    @Test
    void mixedDependencyGraphSchedulesSerialAndConcurrentSegments() throws Exception {
        RecordingApplier inner = new RecordingApplier(Map.of(
                "tx1", 200L, "tx2", 200L, "tx3", 200L,
                "tx4", 80L, "tx5", 80L,
                "tx6", 50L, "tx7", 50L));
        ErrorHandler errorHandler = new ErrorHandler();
        BlockingQueue<ApplierEvent> queue = new ArrayBlockingQueue<>(64);
        TransactionDispatcher dispatcher = dispatcher(4, queue, inner, errorHandler);

        // sequence order = feed order: free txs and the B chain first (they run while the
        // A chain is still queued behind its own conflict wait), the A-chain pair last.
        queue.put(tx("tx6", 1, 0));
        queue.put(tx("tx7", 2, 0));
        queue.put(tx("tx4", 3, 0));
        queue.put(tx("tx5", 4, 3));
        queue.put(tx("tx1", 5, 0));
        queue.put(tx("tx2", 6, 5));
        queue.put(tx("tx3", 7, 6));
        dispatcher.awaitQuiescent(7);

        Map<String, RecordingApplier.Span> byId = inner.byId();
        assertThat(byId).hasSize(7);

        // Conflicting segments serialize on the watermark: a successor starts only after its
        // predecessor's apply returned.
        assertThat(byId.get("tx5").startNanos()).isGreaterThanOrEqualTo(byId.get("tx4").endNanos());
        assertThat(byId.get("tx2").startNanos()).isGreaterThanOrEqualTo(byId.get("tx1").endNanos());
        assertThat(byId.get("tx3").startNanos()).isGreaterThanOrEqualTo(byId.get("tx2").endNanos());

        // Everything non-conflicting overlaps A's first link: free txs and the whole B chain
        // finish well before tx1 does (50/80/160ms vs 200ms of work from ~t=0/80).
        assertThat(byId.get("tx6").endNanos()).isLessThan(byId.get("tx1").endNanos());
        assertThat(byId.get("tx7").endNanos()).isLessThan(byId.get("tx1").endNanos());
        assertThat(byId.get("tx4").endNanos()).isLessThan(byId.get("tx1").endNanos());
        assertThat(byId.get("tx5").endNanos()).isLessThan(byId.get("tx1").endNanos());

        // Completion order, deterministic except the tx6/tx7 pair.
        List<String> completion = inner.completionOrder();
        int tx6 = completion.indexOf("tx6");
        int tx7 = completion.indexOf("tx7");
        assertThat(Math.min(tx6, tx7)).isLessThan(completion.indexOf("tx4"));
        assertThat(completion.indexOf("tx4")).isLessThan(completion.indexOf("tx5"));
        assertThat(completion.indexOf("tx5")).isLessThan(completion.indexOf("tx1"));
        assertThat(completion.indexOf("tx1")).isLessThan(completion.indexOf("tx2"));
        assertThat(completion.indexOf("tx2")).isLessThan(completion.indexOf("tx3"));
    }

    /**
     * Deadlock probe: a conflict chain longer than the worker count (2 workers, 12 links) must
     * still drain. The wait graph is acyclic by construction — a transaction is fed only after its
     * conflicting predecessors committed, so no worker can hold a transaction something else
     * needs — but a regression that reorders wait/feed or parks a waiting transaction on a worker
     * would hang exactly here.
     */
    @Test
    void conflictChainLongerThanWorkerCountDoesNotDeadlock() throws Exception {
        Map<String, Long> delays = new java.util.HashMap<>();
        for (int i = 1; i <= 12; i++) {
            delays.put("tx" + i, 10L);
        }
        RecordingApplier inner = new RecordingApplier(delays);
        ErrorHandler errorHandler = new ErrorHandler();
        BlockingQueue<ApplierEvent> queue = new ArrayBlockingQueue<>(64);
        TransactionDispatcher dispatcher = dispatcher(2, queue, inner, errorHandler);

        for (int i = 1; i <= 12; i++) {
            queue.put(tx("tx" + i, i, i - 1));
        }
        long startMillis = System.currentTimeMillis();
        dispatcher.awaitQuiescent(12);
        long elapsed = System.currentTimeMillis() - startMillis;

        assertThat(inner.byId()).hasSize(12);
        // 12 serial links of 10ms ≈ 120ms; generous ceiling, but a deadlock never gets here.
        assertThat(elapsed).isLessThan(5000);
        // The chain drained in sequence order.
        assertThat(inner.completionOrder()).containsExactlyElementsOf(java.util.stream.IntStream
                .rangeClosed(1, 12)
                .mapToObj(i -> "tx" + i)
                .toList());
    }

    private static ApplierEvent tx(String txId, long sequenceNumber, long lastCommitted) {
        return ApplierEvent.builder()
                .transactionId(txId)
                .sequenceNumber(sequenceNumber)
                .lastCommitted(lastCommitted)
                .canUseWriteSet(true)
                .hasDataChangeEvent(true)
                .records(List.of(
                        txMark(txId, TransactionEvent.Status.BEGIN),
                        row(Operation.CREATE, "s", "a", (int) sequenceNumber, txId)))
                .build();
    }

    private TransactionDispatcher dispatcher(
            int workers,
            BlockingQueue<ApplierEvent> queue,
            Applier<Collection<ChangeEvent>> inner,
            ErrorHandler errorHandler) {
        ReplayConfig config = ReplayConfig.builder()
                .connectionProvider(new UnreachableConnectionProvider())
                .txApplierConcurrency(workers)
                .tableApplierConcurrency(1)
                .transactionBufferSize(64)
                .applierWorkerBufferSize(100)
                .build();
        AtomicBoolean running = new AtomicBoolean(true);
        TransactionDispatcher dispatcher = new TransactionDispatcher(
                queue,
                running::get,
                errorHandler,
                config,
                d -> new ApplierWorker(d, running::get, errorHandler, config, inner));
        // TransactionDispatcher.prepare starts the workers; the dispatcher's own thread needs start() too
        // (production reaches it through TransactionApplier.prepare).
        dispatcher.prepare(TaskContext.empty());
        dispatcher.start();
        dispatchers.add(dispatcher);
        return dispatcher;
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

    /** Fake executor: sleeps the configured delay per transaction, records start/end and order. */
    private static final class RecordingApplier implements Applier<Collection<ChangeEvent>> {
        private final Map<String, Long> delaysMillis;
        private final List<Span> spans = Collections.synchronizedList(new ArrayList<>());

        RecordingApplier(Map<String, Long> delaysMillis) {
            this.delaysMillis = delaysMillis;
        }

        @Override
        public void prepare(TaskContext taskContext) {
        }

        @Override
        public void apply(Collection<ChangeEvent> events) {
            String txId = txIdOf(events);
            long start = System.nanoTime();
            long delay = delaysMillis.getOrDefault(txId, 0L);
            if (delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            spans.add(new Span(txId, start, System.nanoTime()));
        }

        @Override
        public void release() {
        }

        Map<String, Span> byId() {
            Map<String, Span> byId = new java.util.HashMap<>();
            synchronized (spans) {
                for (Span span : spans) {
                    byId.put(span.txId(), span);
                }
            }
            return byId;
        }

        List<String> completionOrder() {
            synchronized (spans) {
                return spans.stream().map(Span::txId).toList();
            }
        }

        private static String txIdOf(Collection<ChangeEvent> events) {
            for (ChangeEvent event : events) {
                if (event instanceof TransactionEvent transactionEvent) {
                    return transactionEvent.getTransactionId();
                }
                if (event instanceof DataChangeEvent dataChangeEvent
                        && dataChangeEvent.getTransactionId() != null) {
                    return dataChangeEvent.getTransactionId();
                }
            }
            return null;
        }

        record Span(String txId, long startNanos, long endNanos) {
        }
    }
}
