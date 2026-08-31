package io.opendrs.debezium;

import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.RecordChangeEvent;
import io.debezium.engine.StopEngineException;
import java.util.List;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unused on the production capture path (one Engine that keeps running after snapshot). Kept as
 * the historical way to exit a schema-only Engine: {@link StopEngineException} after Initial
 * Snapshot {@code COMPLETED}.
 */
public final class SchemaSnapshotStopConsumer
        implements DebeziumEngine.ChangeConsumer<RecordChangeEvent<SourceRecord>> {

    private static final Logger log = LoggerFactory.getLogger(SchemaSnapshotStopConsumer.class);

    private final DebeziumEngine.ChangeConsumer<RecordChangeEvent<SourceRecord>> delegate;

    public SchemaSnapshotStopConsumer(
            DebeziumEngine.ChangeConsumer<RecordChangeEvent<SourceRecord>> delegate) {
        this.delegate = delegate;
    }

    @Override
    public void handleBatch(
            List<RecordChangeEvent<SourceRecord>> records,
            DebeziumEngine.RecordCommitter<RecordChangeEvent<SourceRecord>> committer)
            throws InterruptedException {
        boolean snapshotCompleted = false;
        for (RecordChangeEvent<SourceRecord> event : records) {
            if (isInitialSnapshotCompleted(event.record())) {
                snapshotCompleted = true;
            }
        }
        delegate.handleBatch(records, committer);
        if (snapshotCompleted) {
            log.info("SCHEMA_SNAPSHOT completed; stopping Engine");
            throw new StopEngineException("SCHEMA_SNAPSHOT completed");
        }
    }

    static boolean isInitialSnapshotCompleted(SourceRecord record) {
        return SnapshotNotifications.isInitialSnapshotCompleted(record);
    }
}
