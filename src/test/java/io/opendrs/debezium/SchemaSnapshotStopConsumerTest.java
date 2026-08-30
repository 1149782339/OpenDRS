package io.opendrs.debezium;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.RecordChangeEvent;
import io.debezium.engine.StopEngineException;
import java.util.List;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.Test;

class SchemaSnapshotStopConsumerTest {

    @Test
    void throwsStopEngineExceptionAfterCompletedNotificationIsCommitted() throws Exception {
        RecordingChangeConsumer recording = new RecordingChangeConsumer(1L);
        SchemaSnapshotStopConsumer consumer = new SchemaSnapshotStopConsumer(recording);
        SourceRecord completed = notification("Initial Snapshot", "COMPLETED");
        FakeCommitter committer = new FakeCommitter();

        assertThatThrownBy(() -> consumer.handleBatch(List.of(() -> completed), committer))
                .isInstanceOf(StopEngineException.class)
                .hasMessageContaining("SCHEMA_SNAPSHOT completed");
        assertThat(committer.batchFinished).isTrue();
        assertThat(recording.records()).hasSize(1);
    }

    @Test
    void doesNotStopOnSchemaChangeRecords() throws Exception {
        RecordingChangeConsumer recording = new RecordingChangeConsumer(1L);
        SchemaSnapshotStopConsumer consumer = new SchemaSnapshotStopConsumer(recording);
        Schema valueSchema = SchemaBuilder.struct().field("op", Schema.OPTIONAL_STRING_SCHEMA).build();
        SourceRecord schemaChange = new SourceRecord(
                null, null, "opendrs.task.1", Schema.STRING_SCHEMA, "k", valueSchema, new Struct(valueSchema));
        FakeCommitter committer = new FakeCommitter();

        consumer.handleBatch(List.of(() -> schemaChange), committer);
        assertThat(committer.batchFinished).isTrue();
        assertThat(recording.records()).hasSize(1);
    }

    @Test
    void recognizesInitialSnapshotCompletedPayload() {
        assertThat(SchemaSnapshotStopConsumer.isInitialSnapshotCompleted(
                        notification("Initial Snapshot", "COMPLETED")))
                .isTrue();
        assertThat(SchemaSnapshotStopConsumer.isInitialSnapshotCompleted(
                        notification("Initial Snapshot", "STARTED")))
                .isFalse();
    }

    private static SourceRecord notification(String aggregateType, String type) {
        Schema schema = SchemaBuilder.struct()
                .field("aggregate_type", Schema.STRING_SCHEMA)
                .field("type", Schema.STRING_SCHEMA)
                .build();
        Struct value = new Struct(schema).put("aggregate_type", aggregateType).put("type", type);
        return new SourceRecord(null, null, "opendrs.task.1.notification", schema, value);
    }

    private static final class FakeCommitter
            implements DebeziumEngine.RecordCommitter<RecordChangeEvent<SourceRecord>> {
        boolean batchFinished;

        @Override
        public void markProcessed(RecordChangeEvent<SourceRecord> record) {
        }

        @Override
        public void markBatchFinished() {
            batchFinished = true;
        }

        @Override
        public void markProcessed(RecordChangeEvent<SourceRecord> record, DebeziumEngine.Offsets sourceOffsets) {
        }

        @Override
        public DebeziumEngine.Offsets buildOffsets() {
            return null;
        }
    }
}
