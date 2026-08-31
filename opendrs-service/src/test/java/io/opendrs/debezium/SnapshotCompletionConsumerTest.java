package io.opendrs.debezium;

import static org.assertj.core.api.Assertions.assertThat;

import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.RecordChangeEvent;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.Test;

class SnapshotCompletionConsumerTest {

    @Test
    void signalsOnceOnCompletedNotificationWithoutStopping() throws Exception {
        RecordingChangeConsumer recording = new RecordingChangeConsumer(1L);
        AtomicInteger signaled = new AtomicInteger();
        SnapshotCompletionConsumer consumer = new SnapshotCompletionConsumer(recording, signaled::incrementAndGet);
        SourceRecord completed = notification("Initial Snapshot", "COMPLETED");
        FakeCommitter committer = new FakeCommitter();

        consumer.handleBatch(List.of(() -> completed), committer);
        consumer.handleBatch(List.of(() -> completed), committer);

        assertThat(signaled.get()).isEqualTo(1);
        assertThat(committer.batchFinished).isTrue();
        assertThat(recording.records()).hasSize(2);
    }

    @Test
    void doesNotSignalOnSchemaChangeRecords() throws Exception {
        RecordingChangeConsumer recording = new RecordingChangeConsumer(1L);
        AtomicInteger signaled = new AtomicInteger();
        SnapshotCompletionConsumer consumer = new SnapshotCompletionConsumer(recording, signaled::incrementAndGet);
        Schema valueSchema = SchemaBuilder.struct().field("op", Schema.OPTIONAL_STRING_SCHEMA).build();
        SourceRecord schemaChange = new SourceRecord(
                null, null, "opendrs.task.1", Schema.STRING_SCHEMA, "k", valueSchema, new Struct(valueSchema));
        FakeCommitter committer = new FakeCommitter();

        consumer.handleBatch(List.of(() -> schemaChange), committer);
        assertThat(signaled.get()).isZero();
        assertThat(recording.records()).hasSize(1);
    }

    @Test
    void signalsWhenStreamingInsertArrives() throws Exception {
        RecordingChangeConsumer recording = new RecordingChangeConsumer(1L);
        AtomicInteger signaled = new AtomicInteger();
        SnapshotCompletionConsumer consumer = new SnapshotCompletionConsumer(recording, signaled::incrementAndGet);
        Schema valueSchema = SchemaBuilder.struct().field("op", Schema.OPTIONAL_STRING_SCHEMA).build();
        Struct value = new Struct(valueSchema).put("op", "c");
        SourceRecord insert = new SourceRecord(
                null, null, "opendrs.task.1", Schema.STRING_SCHEMA, "k", valueSchema, value);
        FakeCommitter committer = new FakeCommitter();

        consumer.handleBatch(List.of(() -> insert), committer);
        assertThat(signaled.get()).isEqualTo(1);
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
