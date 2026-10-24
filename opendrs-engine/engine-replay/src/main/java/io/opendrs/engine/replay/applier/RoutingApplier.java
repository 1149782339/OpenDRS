package io.opendrs.engine.replay.applier;

import io.opendrs.engine.replay.ReplayConfig;
import io.opendrs.engine.replay.context.ConnectorContext;
import io.opendrs.engine.replay.context.TaskContext;
import io.opendrs.engine.replay.event.ChangeEvent;
import io.opendrs.engine.replay.event.DataChangeEvent;
import io.opendrs.engine.replay.event.Operation;
import io.opendrs.engine.replay.exception.ApplierException;
import io.opendrs.engine.replay.util.ErrorHandler;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Routes each incoming batch by event kind: snapshot rows ({@code Operation.READ}, no transaction
 * context) go to {@link TableApplier} for table-parallel apply; everything else goes to
 * {@link TransactionApplier} for write-set-scheduled transaction apply. Per-batch splitting keeps
 * the snapshot/streaming transition race-free — no phase signal is trusted.
 */
public class RoutingApplier implements Applier<Collection<ChangeEvent>> {

    private final TableApplier tableApplier;

    private final TransactionApplier transactionApplier;

    public RoutingApplier(ConnectorContext context, ErrorHandler errorHandler, ReplayConfig config) {
        this.tableApplier = new TableApplier(context, config);
        this.transactionApplier = new TransactionApplier(context, errorHandler, config);
    }

    /** Test seam: pre-built delegates. */
    RoutingApplier(TableApplier tableApplier, TransactionApplier transactionApplier) {
        this.tableApplier = tableApplier;
        this.transactionApplier = transactionApplier;
    }

    @Override
    public void prepare(TaskContext taskContext) {
        tableApplier.prepare(taskContext);
        transactionApplier.prepare(taskContext);
    }

    @Override
    public void release() {
        transactionApplier.release();
        tableApplier.release();
    }

    @Override
    public synchronized void apply(Collection<ChangeEvent> changeEvents) throws ApplierException {
        if (changeEvents == null || changeEvents.isEmpty()) {
            return;
        }
        // Snapshot events (read events) are not transactional and can't be applied by the
        // transaction applier; split per batch so a batch straddling the snapshot/streaming
        // transition routes each event to the right executor.
        List<ChangeEvent> snapshotEvents = new ArrayList<>(changeEvents.size());
        List<ChangeEvent> nonSnapshotEvents = new ArrayList<>(changeEvents.size());
        for (ChangeEvent changeEvent : changeEvents) {
            if (changeEvent instanceof DataChangeEvent dataChangeEvent
                    && dataChangeEvent.getOperation() == Operation.READ) {
                snapshotEvents.add(dataChangeEvent);
            } else {
                nonSnapshotEvents.add(changeEvent);
            }
        }
        tableApplier.apply(snapshotEvents);
        transactionApplier.apply(nonSnapshotEvents);
    }
}
