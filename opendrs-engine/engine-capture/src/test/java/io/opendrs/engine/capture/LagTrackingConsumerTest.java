package io.opendrs.engine.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.RecordChangeEvent;
import io.opendrs.common.progress.MigrationLagListener;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.Test;

class LagTrackingConsumerTest {

    private final List<String> calls = new ArrayList<>();
    private final MigrationLagListener recorder = new MigrationLagListener() {
        @Override
        public void onApplied(long sourceEventTimeMs, long appliedAtMs) {
            calls.add("applied:" + sourceEventTimeMs + ":" + appliedAtMs);
        }

        @Override
        public void onHeartbeat(long seenAtMs) {
            calls.add("heartbeat:" + seenAtMs);
        }
    };

    @Test
    void publishesTheNewestStreamingEventOfTheBatch() throws Exception {
        long before = System.currentTimeMillis();

        track(streaming(1_000L), streaming(2_000L));

        assertThat(calls).hasSize(1);
        String call = calls.get(0);
        assertThat(call).startsWith("applied:2000:");
        assertThat(appliedAt(call)).isGreaterThanOrEqualTo(before);
    }

    @Test
    void ignoresSnapshotRows() throws Exception {
        // A snapshot row carries source.ts_ms too, but it is the read time: counting it would make
        // a task that is still copying rows look caught up.
        track(snapshot(9_000L));

        assertThat(calls).isEmpty();
    }

    @Test
    void heartbeatReportsLivenessWhenNoChangeArrives() throws Exception {
        track(heartbeat());

        assertThat(calls).hasSize(1);
        assertThat(calls.get(0)).startsWith("heartbeat:");
    }

    @Test
    void dataInTheSameBatchWinsOverTheHeartbeat() throws Exception {
        track(heartbeat(), streaming(4_000L));

        assertThat(calls).hasSize(1);
        assertThat(calls.get(0)).startsWith("applied:4000:");
    }

    @Test
    void recordsWithoutASourceStructPublishNothing() throws Exception {
        Schema schema = SchemaBuilder.struct().field("op", Schema.STRING_SCHEMA).build();
        SourceRecord schemaChange =
                new SourceRecord(null, null, "opendrs.task.1", null, null, schema, new Struct(schema).put("op", "c"));

        track(schemaChange);

        assertThat(calls).isEmpty();
    }

    @Test
    void rejectedBatchPublishesNothingBecauseNothingWasCommitted() {
        LagTrackingConsumer consumer = new LagTrackingConsumer((records, committer) -> {
            throw new IllegalStateException("target refused the batch");
        }, recorder);

        assertThatThrownBy(() -> consumer.handleBatch(List.of(() -> streaming(1_000L)), new FakeCommitter()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(calls).isEmpty();
    }

    private void track(SourceRecord... records) throws InterruptedException {
        LagTrackingConsumer consumer = new LagTrackingConsumer((batch, committer) -> {
            for (RecordChangeEvent<SourceRecord> event : batch) {
                committer.markProcessed(event);
            }
            committer.markBatchFinished();
        }, recorder);
        List<RecordChangeEvent<SourceRecord>> batch = new ArrayList<>();
        for (SourceRecord record : records) {
            batch.add(() -> record);
        }
        consumer.handleBatch(batch, new FakeCommitter());
    }

    private static long appliedAt(String call) {
        return Long.parseLong(call.split(":")[2]);
    }

    private static SourceRecord streaming(long eventTimeMs) {
        Schema source = sourceSchema(false);
        Schema value = SchemaBuilder.struct()
                .field("op", Schema.STRING_SCHEMA)
                .field("source", source)
                .build();
        Struct envelope = new Struct(value)
                .put("op", "c")
                .put("source", new Struct(source).put("ts_ms", eventTimeMs));
        return record(value, envelope);
    }

    private static SourceRecord snapshot(long readTimeMs) {
        Schema source = sourceSchema(true);
        Schema value = SchemaBuilder.struct()
                .field("op", Schema.STRING_SCHEMA)
                .field("source", source)
                .build();
        Struct envelope = new Struct(value)
                .put("op", "r")
                .put("source", new Struct(source).put("ts_ms", readTimeMs).put("snapshot", "true"));
        return record(value, envelope);
    }

    /** Debezium's embedded heartbeat has no value at all, only a position. */
    private static SourceRecord heartbeat() {
        return new SourceRecord(
                Map.of("server", "opendrs"), Map.of("file", "mysql-bin.000001", "pos", 4L),
                "opendrs.task.1", null, null, null, null);
    }

    private static Schema sourceSchema(boolean withSnapshotMarker) {
        SchemaBuilder builder = SchemaBuilder.struct().field("ts_ms", Schema.INT64_SCHEMA);
        if (withSnapshotMarker) {
            builder.field("snapshot", Schema.OPTIONAL_STRING_SCHEMA);
        }
        return builder.build();
    }

    private static SourceRecord record(Schema valueSchema, Struct value) {
        return new SourceRecord(
                Map.of("server", "opendrs"), Map.of("file", "mysql-bin.000001", "pos", 4L),
                "opendrs.task.1.shop.orders", null, null, valueSchema, value);
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
