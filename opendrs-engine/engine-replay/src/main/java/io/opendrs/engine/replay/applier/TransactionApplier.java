package io.opendrs.engine.replay.applier;

import io.opendrs.engine.replay.ReplayConfig;
import io.opendrs.engine.replay.context.ConnectorContext;
import io.opendrs.engine.replay.context.TaskContext;
import io.opendrs.engine.replay.event.ApplierEvent;
import io.opendrs.engine.replay.event.ChangeEvent;
import io.opendrs.engine.replay.event.DataChangeEvent;
import io.opendrs.engine.replay.event.SchemaChangeEvent;
import io.opendrs.engine.replay.event.TransactionEvent;
import io.opendrs.engine.replay.exception.ApplierException;
import io.opendrs.engine.replay.relation.FieldsMetaData;
import io.opendrs.engine.replay.transaction.Transaction;
import io.opendrs.engine.replay.util.ErrorHandler;
import io.opendrs.engine.replay.util.WriteSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parallel transaction applier, packets change events as transactions,
 * and parallel applies these transactions by using write set algorithm
 *
 * <p>Parallelism is a config knob ({@code txApplierConcurrency = 1} is serial per-transaction
 * replay); the transaction unit is what defines the class.
 *
 * <p>Phase-1 contract: {@link #apply(Collection)} returns only after every transaction received
 * so far has been applied (or failed). The change consumer relies on this to keep Debezium
 * offset commits at-least-once without touching the offset store. Cross-batch pipelining is
 * deliberately sacrificed in this phase.
 *
 */
public class TransactionApplier implements Applier<Collection<ChangeEvent>> {

    private final static Logger LOGGER = LoggerFactory.getLogger(TransactionApplier.class);

    private final BlockingQueue<ApplierEvent> queue;

    private final TransactionDispatcher dispatcher;

    private final Map<String, Transaction> transactionMap = new LinkedHashMap<>();

    private long lastCommitted;

    private long sequenceNumber;

    private String transactionId = null;

    private WriteSet writeSet = new WriteSet();

    /** Cumulative dispatched units; the barrier target handed to {@link TransactionDispatcher#awaitQuiescent}. */
    private long dispatchedTotal;

    public TransactionApplier(
            ConnectorContext connectorContext,
            ErrorHandler errorHandler,
            ReplayConfig config) {
        this.lastCommitted = 0L;
        this.sequenceNumber = 1L;
        this.queue = new ArrayBlockingQueue<>(config.getTransactionBufferSize());
        this.dispatcher = new TransactionDispatcher(queue, connectorContext, errorHandler, config);
    }

    /** Test seam: assemble against a caller-supplied dispatcher (fake workers, no JDBC). */
    TransactionApplier(BlockingQueue<ApplierEvent> queue, TransactionDispatcher dispatcher) {
        this.lastCommitted = 0L;
        this.sequenceNumber = 1L;
        this.queue = queue;
        this.dispatcher = dispatcher;
    }

    @Override
    public void prepare(TaskContext taskContext) {
        dispatcher.prepare(taskContext);
        dispatcher.start();
    }

    @Override
    public void apply(Collection<ChangeEvent> events) throws ApplierException {
        try {
            for (ChangeEvent event : events) {
                String transactionId = event.getTransactionId();
                if (transactionId == null) {
                    // No transaction context (DDL between transactions, heartbeat-like rows
                    // during snapshot): a self-contained unit that dispatches immediately.
                    dispatchApplierEvent(Transaction.of(event));
                    this.transactionId = null;
                    continue;
                }
                if (event instanceof TransactionEvent) {
                    handleTransactionEvent((TransactionEvent) event);
                    continue;
                }
                if (this.transactionId == null) {
                    this.transactionId = transactionId;
                }
                Transaction transaction = transactionMap.get(transactionId);
                if (transaction == null) {
                    transaction = new Transaction(transactionId);
                    transactionMap.put(transactionId, transaction);
                }
                transaction.addEvent(event);
                if (!transactionId.equals(this.transactionId)) {
                    transactionMap.remove(transactionId);
                    dispatchApplierEvent(transaction);
                }
                this.transactionId = transactionId;
            }
            // Phase-1 barrier: nothing may return to the change consumer while anything dispatched
            // so far is unapplied (still queued, in conflict-wait, or executing), or a crash
            // would commit offsets past unapplied work.
            dispatcher.awaitQuiescent(dispatchedTotal);
        } catch (InterruptedException e) {
            LOGGER.warn("InterruptedException");
            Thread.currentThread().interrupt();
            throw new ApplierException("failed to apply", e);
        }
    }

    private void handleTransactionEvent(TransactionEvent event) throws InterruptedException {
        TransactionEvent.Status status = event.getStatus();
        String transactionId = event.getTransactionId();
        Transaction transaction;
        if (status == TransactionEvent.Status.BEGIN) {
            transaction = new Transaction(transactionId);
            transaction.addEvent(event);
            transactionMap.put(transactionId, transaction);
            this.transactionId = transactionId;
            return;
        }
        transaction = transactionMap.remove(transactionId);
        if (transaction == null) {
            return;
        }
        transaction.addEvent(event);
        dispatchApplierEvent(transaction);
    }

    private void dispatchApplierEvent(Transaction transaction) throws InterruptedException {
        boolean canUseWriteSet = true;
        // if there is a dll event or a dml event involving any tables
        // without primary keys in the transaction, in this case, write set
        // can't be used
        for (ChangeEvent event : transaction.getEvents()) {
            if (event instanceof DataChangeEvent) {
                FieldsMetaData fieldsMetaData = ((DataChangeEvent) event).getFieldsMetaData();
                if (fieldsMetaData == null || fieldsMetaData.getPrimaryKeyFieldNames().isEmpty()) {
                    canUseWriteSet = false;
                    break;
                }
            } else if (event instanceof SchemaChangeEvent) {
                canUseWriteSet = false;
                break;
            }
        }
        // distribute lastCommitted and sequenceNumber for a transactional event
        ApplierEvent applierEvent = ApplierEvent
            .builder()
            .lastCommitted(lastCommitted++)
            .sequenceNumber(sequenceNumber++)
            .records(transaction.getEvents())
            .transactionId(transaction.getId())
            .canUseWriteSet(canUseWriteSet)
            .hasDataChangeEvent(transaction.hasDataChangeEvent())
            .build();
        if (applierEvent.hasDataChangeEvent()) {
            writeSet.add(applierEvent);
        }
        queue.put(applierEvent);
        dispatchedTotal++;
    }

    @Override
    public void release() {
        dispatcher.stop();
        dispatcher.release();
    }
}
