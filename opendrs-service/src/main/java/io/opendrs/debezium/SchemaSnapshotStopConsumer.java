package io.opendrs.debezium;

import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.RecordChangeEvent;
import io.debezium.engine.StopEngineException;
import java.util.List;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SCHEMA_SNAPSHOT consumer: logs records, then stops the Engine when Debezium emits the Initial
 * Snapshot {@code COMPLETED} notification. AsyncEmbeddedEngine keeps polling after
 * {@code shouldStream=false}; {@link StopEngineException} is the supported way to exit.
 */
public final class SchemaSnapshotStopConsumer
        implements DebeziumEngine.ChangeConsumer<RecordChangeEvent<SourceRecord>> {

    static final String INITIAL_SNAPSHOT = "Initial Snapshot";
    static final String COMPLETED = "COMPLETED";

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
        Object value = record.value();
        if (!(value instanceof Struct struct)) {
            return false;
        }
        if (struct.schema().field("aggregate_type") == null || struct.schema().field("type") == null) {
            return false;
        }
        return INITIAL_SNAPSHOT.equals(struct.getString("aggregate_type"))
                && COMPLETED.equals(struct.getString("type"));
    }
}
