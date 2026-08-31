package io.opendrs.debezium;

import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.RecordChangeEvent;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delegates every record to the sink (or test) consumer and signals once when the initial snapshot
 * is done. Does not stop the Engine.
 */
public final class SnapshotCompletionConsumer
        implements DebeziumEngine.ChangeConsumer<RecordChangeEvent<SourceRecord>> {

    private static final Logger log = LoggerFactory.getLogger(SnapshotCompletionConsumer.class);

    private final DebeziumEngine.ChangeConsumer<RecordChangeEvent<SourceRecord>> delegate;
    private final Runnable onSnapshotCompleted;
    private final AtomicBoolean signaled = new AtomicBoolean(false);

    public SnapshotCompletionConsumer(
            DebeziumEngine.ChangeConsumer<RecordChangeEvent<SourceRecord>> delegate,
            Runnable onSnapshotCompleted) {
        this.delegate = delegate;
        this.onSnapshotCompleted = onSnapshotCompleted;
    }

    @Override
    public void handleBatch(
            List<RecordChangeEvent<SourceRecord>> records,
            DebeziumEngine.RecordCommitter<RecordChangeEvent<SourceRecord>> committer)
            throws InterruptedException {
        boolean snapshotCompleted = false;
        boolean streaming = false;
        for (RecordChangeEvent<SourceRecord> event : records) {
            SourceRecord record = event.record();
            if (SnapshotNotifications.isInitialSnapshotCompleted(record)) {
                snapshotCompleted = true;
            }
            Object op = LoggingChangeConsumer.extractOp(record);
            if ("c".equals(op) || "u".equals(op) || "d".equals(op)) {
                streaming = true;
            }
        }
        delegate.handleBatch(records, committer);
        if ((snapshotCompleted || streaming) && signaled.compareAndSet(false, true)) {
            log.info(
                    snapshotCompleted
                            ? "Initial snapshot completed; Engine continues streaming"
                            : "Streaming events observed; treating snapshot as complete");
            signalCompleted();
        }
    }

    private void signalCompleted() {
        if (onSnapshotCompleted == null) {
            return;
        }
        try {
            onSnapshotCompleted.run();
        } catch (RuntimeException ex) {
            log.error("Snapshot-completed callback failed", ex);
        }
    }
}
