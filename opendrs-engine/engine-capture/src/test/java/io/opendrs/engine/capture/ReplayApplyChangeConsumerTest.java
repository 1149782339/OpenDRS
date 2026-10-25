package io.opendrs.engine.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.RecordChangeEvent;
import io.opendrs.common.progress.FullLoadCheckpointListener;
import io.opendrs.engine.replay.ReplayConfig;
import io.opendrs.engine.replay.applier.Applier;
import io.opendrs.engine.replay.context.TaskContext;
import io.opendrs.engine.replay.exception.ApplierException;
import io.opendrs.engine.replay.metrics.ReplayDirection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.Test;

class ReplayApplyChangeConsumerTest {

    private static final Schema NOTIFICATION_SCHEMA = SchemaBuilder.struct()
            .field("aggregate_type", Schema.STRING_SCHEMA)
            .field("type", Schema.STRING_SCHEMA)
            .field("additional_data", SchemaBuilder.map(Schema.STRING_SCHEMA, Schema.STRING_SCHEMA))
            .build();

    private final List<String> events = new ArrayList<>();
    private final AtomicInteger applyCalls = new AtomicInteger();

    /** No-op applier that just records apply() invocations (before/after listener callbacks). */
    private final Applier<Collection<SourceRecord>> fakeApplier = new Applier<>() {
        @Override
        public void prepare(TaskContext taskContext) {
        }

        @Override
        public void apply(Collection<SourceRecord> records) {
            applyCalls.incrementAndGet();
            events.add("applied:" + records.size());
        }

        @Override
        public void release() {
        }
    };

    private static final long TASK_ID = 7L;
    private static final long PIPELINE_ID = 41L;

    private final FullLoadCheckpointListener recordingListener = new FullLoadCheckpointListener() {
        @Override
        public void onTableFinished(long pipelineId, String table, long rowCount) {
            events.add("table:" + pipelineId + ":" + table + ":" + rowCount);
        }
    };

    @Test
    void reportsNotificationsOnlyAfterApplyReturns() throws Exception {
        ReplayApplyChangeConsumer consumer = consumer(recordingListener, fakeApplier);
        List<RecordChangeEvent<SourceRecord>> batch = List.of(
                () -> dataRecord(),
                () -> notification("DATA_COLLECTIONS_RESOLVED",
                        Map.of("data_collections", "hr.dept,hr.emp")),
                () -> notification("TABLE_SCAN_COMPLETED", Map.of(
                        "scanned_collection", "hr.dept",
                        "total_rows_scanned", "7",
                        "status", "SUCCEEDED")));

        consumer.handleBatch(batch, new FakeCommitter());

        assertThat(applyCalls.get()).isEqualTo(1);
        // The resolved list is observability-only now (logListener, not persisted); only the
        // finished table reaches the checkpoint listener, keyed by pipeline id.
        assertThat(events).containsExactly(
                "applied:3",
                "table:41:hr.dept:7");
    }

    @Test
    void reportsEveryTableScanCompletedInTheBatch() throws Exception {
        ReplayApplyChangeConsumer consumer = consumer(recordingListener, fakeApplier);
        List<RecordChangeEvent<SourceRecord>> batch = List.of(
                () -> notification("TABLE_SCAN_COMPLETED", Map.of(
                        "scanned_collection", "hr.dept",
                        "total_rows_scanned", "3",
                        "status", "SUCCEEDED")),
                () -> notification("TABLE_SCAN_COMPLETED", Map.of(
                        "scanned_collection", "hr.emp",
                        "total_rows_scanned", "5",
                        "status", "SUCCEEDED")));

        consumer.handleBatch(batch, new FakeCommitter());

        assertThat(events).containsExactly(
                "applied:2",
                "table:41:hr.dept:3",
                "table:41:hr.emp:5");
    }

    @Test
    void failedScanIsLoggedButNeverMarkedDone() throws Exception {
        ReplayApplyChangeConsumer consumer = consumer(recordingListener, fakeApplier);

        consumer.handleBatch(List.of(() -> notification("TABLE_SCAN_COMPLETED", Map.of(
                "scanned_collection", "hr.emp",
                "status", "SQL_EXCEPTION"))), new FakeCommitter());

        assertThat(events).containsExactly("applied:1");
    }

    @Test
    void retriesThreeTimesThenFailsTheTask() {
        AtomicInteger attempts = new AtomicInteger();
        FullLoadCheckpointListener failing = new FullLoadCheckpointListener() {
            @Override
            public void onTableFinished(long pipelineId, String table, long rowCount) {
                attempts.incrementAndGet();
                throw new RuntimeException("metadata db down");
            }
        };
        ReplayApplyChangeConsumer consumer = consumer(failing, fakeApplier);

        assertThatThrownBy(() -> consumer.handleBatch(List.of(() -> notification("TABLE_SCAN_COMPLETED", Map.of(
                "scanned_collection", "hr.dept",
                "total_rows_scanned", "7",
                "status", "SUCCEEDED"))), new FakeCommitter()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hr.dept")
                .hasMessageContaining("41");

        assertThat(attempts.get()).isEqualTo(3);
        // apply() ran and committed; the throw happens after it.
        assertThat(applyCalls.get()).isEqualTo(1);
    }

    @Test
    void keepsQuietWhenListenerIsNone() throws Exception {
        ReplayApplyChangeConsumer consumer = consumer(null, fakeApplier);

        consumer.handleBatch(List.of(() -> notification("TABLE_SCAN_COMPLETED", Map.of(
                "scanned_collection", "hr.dept",
                "total_rows_scanned", "7",
                "status", "SUCCEEDED"))), new FakeCommitter());

        assertThat(events).containsExactly("applied:1");
    }

    private ReplayApplyChangeConsumer consumer(
            FullLoadCheckpointListener listener, Applier<Collection<SourceRecord>> applier) {
        ReplayConfig config = ReplayConfig.builder()
                .connectionProvider(() -> {
                    throw new UnsupportedOperationException("no target in unit test");
                })
                .direction(ReplayDirection.REVERSE)
                .build();
        return new ReplayApplyChangeConsumer(
                TASK_ID, PIPELINE_ID, config, null, null, listener, applier, 0);
    }

    private static SourceRecord dataRecord() {
        Schema schema = SchemaBuilder.struct().field("op", Schema.STRING_SCHEMA).build();
        return new SourceRecord(
                Map.of("server", "opendrs.pipeline.7"), Map.of(), "opendrs.pipeline.7.hr.emp",
                null, null, schema, new Struct(schema).put("op", "r"));
    }

    private static SourceRecord notification(String type, Map<String, String> additionalData) {
        Struct value = new Struct(NOTIFICATION_SCHEMA)
                .put("aggregate_type", "Initial Snapshot")
                .put("type", type)
                .put("additional_data", additionalData);
        return new SourceRecord(
                Map.of("server", "opendrs.pipeline.7"), Map.of(),
                "opendrs.pipeline.7.notification", NOTIFICATION_SCHEMA, value);
    }

    private static final class FakeCommitter
            implements DebeziumEngine.RecordCommitter<RecordChangeEvent<SourceRecord>> {
        @Override
        public void markProcessed(RecordChangeEvent<SourceRecord> record) {
        }

        @Override
        public void markBatchFinished() {
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
